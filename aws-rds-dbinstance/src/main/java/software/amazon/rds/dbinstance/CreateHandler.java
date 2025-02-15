package software.amazon.rds.dbinstance;

import java.util.Collection;
import java.util.Collections;

import org.apache.commons.lang3.BooleanUtils;

import com.amazonaws.util.StringUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBSnapshot;
import software.amazon.awssdk.utils.ImmutableMap;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.Logger;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.ProxyClient;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;
import software.amazon.rds.common.handler.Commons;
import software.amazon.rds.common.handler.HandlerConfig;
import software.amazon.rds.common.handler.HandlerMethod;
import software.amazon.rds.common.handler.Tagging;
import software.amazon.rds.common.util.IdentifierFactory;
import software.amazon.rds.dbinstance.client.ApiVersion;
import software.amazon.rds.dbinstance.client.VersionedProxyClient;
import software.amazon.rds.dbinstance.request.RequestValidationException;
import software.amazon.rds.dbinstance.request.ValidatedRequest;
import software.amazon.rds.dbinstance.util.ResourceModelHelper;

public class CreateHandler extends BaseHandlerStd {

    private static final IdentifierFactory instanceIdentifierFactory = new IdentifierFactory(
            STACK_NAME,
            RESOURCE_IDENTIFIER,
            RESOURCE_ID_MAX_LENGTH
    );

    public CreateHandler() {
        this(DB_INSTANCE_HANDLER_CONFIG_36H);
    }

    public CreateHandler(final HandlerConfig config) {
        super(config);
    }

    @Override
    protected void validateRequest(final ResourceHandlerRequest<ResourceModel> request) throws RequestValidationException {
        super.validateRequest(request);
        validateDeletionPolicyForClusterInstance(request);
    }

    private void validateDeletionPolicyForClusterInstance(final ResourceHandlerRequest<ResourceModel> request) throws RequestValidationException {
        if (isDBClusterMember(request.getDesiredResourceState()) && BooleanUtils.isTrue(request.getSnapshotRequested())) {
            throw new RequestValidationException(ILLEGAL_DELETION_POLICY_ERROR);
        }
    }

    protected ProgressEvent<ResourceModel, CallbackContext> handleRequest(
            final AmazonWebServicesClientProxy proxy,
            final ValidatedRequest<ResourceModel> request,
            final CallbackContext callbackContext,
            final VersionedProxyClient<RdsClient> rdsProxyClient,
            final VersionedProxyClient<Ec2Client> ec2ProxyClient,
            final Logger logger
    ) {
        final ResourceModel model = request.getDesiredResourceState();
        final Collection<DBInstanceRole> desiredRoles = model.getAssociatedRoles();

        if (StringUtils.isNullOrEmpty(model.getDBInstanceIdentifier())) {
            model.setDBInstanceIdentifier(instanceIdentifierFactory.newIdentifier()
                    .withStackId(request.getStackId())
                    .withResourceId(request.getLogicalResourceIdentifier())
                    .withRequestToken(request.getClientRequestToken())
                    .toString());
        }

        final Tagging.TagSet allTags = Tagging.TagSet.builder()
                .systemTags(Tagging.translateTagsToSdk(request.getSystemTags()))
                .stackTags(Tagging.translateTagsToSdk(request.getDesiredResourceTags()))
                .resourceTags(Translator.translateTagsToSdk(request.getDesiredResourceState().getTags()))
                .build();

        return ProgressEvent.progress(model, callbackContext)
                .then(progress -> Commons.execOnce(progress, () -> {
                    if (ResourceModelHelper.isReadReplica(progress.getResourceModel())) {
                        // createDBInstanceReadReplica is not a versioned call, unlike the others.
                        return safeAddTags(this::createDbInstanceReadReplica)
                                .invoke(proxy, rdsProxyClient.defaultClient(), progress, allTags);
                    } else if (ResourceModelHelper.isRestoreFromSnapshot(progress.getResourceModel())) {
                        if (model.getMultiAZ() == null) {
                            try {
                                final DBSnapshot snapshot = fetchDBSnapshot(rdsProxyClient.defaultClient(), model);
                                final String engine = snapshot.engine();
                                progress.getResourceModel().setMultiAZ(ResourceModelHelper.getDefaultMultiAzForEngine(engine));
                            } catch (Exception e) {
                                return Commons.handleException(progress, e, RESTORE_DB_INSTANCE_ERROR_RULE_SET);
                            }
                        }
                        return versioned(proxy, rdsProxyClient, progress, allTags, ImmutableMap.of(
                                ApiVersion.V12, this::restoreDbInstanceFromSnapshotV12,
                                ApiVersion.DEFAULT, safeAddTags(this::restoreDbInstanceFromSnapshot)
                        ));
                    }
                    return versioned(proxy, rdsProxyClient, progress, allTags, ImmutableMap.of(
                            ApiVersion.V12, this::createDbInstanceV12,
                            ApiVersion.DEFAULT, safeAddTags(this::createDbInstance)
                    ));
                }, CallbackContext::isCreated, CallbackContext::setCreated))
                .then(progress -> Commons.execOnce(progress, () -> {
                    final Tagging.TagSet extraTags = Tagging.TagSet.builder()
                            .stackTags(allTags.getStackTags())
                            .resourceTags(allTags.getResourceTags())
                            .build();
                    return updateTags(proxy, rdsProxyClient.defaultClient(), progress, Tagging.TagSet.emptySet(), extraTags);
                }, CallbackContext::isAddTagsComplete, CallbackContext::setAddTagsComplete))
                .then(progress -> ensureEngineSet(rdsProxyClient.defaultClient(), progress))
                .then(progress -> {
                    if (ResourceModelHelper.shouldUpdateAfterCreate(progress.getResourceModel())) {
                        return Commons.execOnce(progress, () ->
                                                versioned(proxy, rdsProxyClient, progress, null, ImmutableMap.of(
                                                        ApiVersion.V12, (pxy, pcl, prg, tgs) -> updateDbInstanceAfterCreateV12(pxy, request, pcl, prg),
                                                        ApiVersion.DEFAULT, (pxy, pcl, prg, tgs) -> updateDbInstanceAfterCreate(pxy, request, pcl, prg)
                                                )),
                                        CallbackContext::isUpdated, CallbackContext::setUpdated)
                                .then(p -> Commons.execOnce(p, () -> {
                                    if (ResourceModelHelper.shouldReboot(p.getResourceModel())) {
                                        return rebootAwait(proxy, rdsProxyClient.defaultClient(), p);
                                    }
                                    return p;
                                }, CallbackContext::isRebooted, CallbackContext::setRebooted));
                    }
                    return progress;
                })
                .then(progress -> Commons.execOnce(progress, () ->
                                updateAssociatedRoles(proxy, rdsProxyClient.defaultClient(), progress, Collections.emptyList(), desiredRoles),
                        CallbackContext::isUpdatedRoles, CallbackContext::setUpdatedRoles))
                .then(progress -> new ReadHandler().handleRequest(proxy, request, progress.getCallbackContext(), rdsProxyClient, ec2ProxyClient, logger));
    }

    private HandlerMethod<ResourceModel, CallbackContext> safeAddTags(final HandlerMethod<ResourceModel, CallbackContext> handlerMethod) {
        return (proxy, rdsProxyClient, progress, tagSet) -> progress.then(p -> Tagging.safeCreate(proxy, rdsProxyClient, handlerMethod, progress, tagSet));
    }

    private ProgressEvent<ResourceModel, CallbackContext> createDbInstanceV12(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress,
            final Tagging.TagSet tagSet
    ) {
        return proxy.initiate(
                        "rds::create-db-instance-v12",
                        rdsProxyClient,
                        progress.getResourceModel(),
                        progress.getCallbackContext()
                ).translateToServiceRequest(Translator::createDbInstanceRequestV12)
                .backoffDelay(config.getBackoff())
                .makeServiceCall((createRequest, proxyInvocation) -> proxyInvocation.injectCredentialsAndInvokeV2(
                        createRequest,
                        proxyInvocation.client()::createDBInstance
                ))
                .stabilize((request, response, proxyInvocation, model, context) ->
                        isDBInstanceStabilizedAfterMutate(proxyInvocation, model))
                .handleError((request, exception, client, model, context) -> Commons.handleException(
                        ProgressEvent.progress(model, context),
                        exception,
                        CREATE_DB_INSTANCE_ERROR_RULE_SET
                ))
                .progress();
    }

    private ProgressEvent<ResourceModel, CallbackContext> createDbInstance(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress,
            final Tagging.TagSet tagSet
    ) {
        return proxy.initiate(
                        "rds::create-db-instance",
                        rdsProxyClient,
                        progress.getResourceModel(),
                        progress.getCallbackContext()
                ).translateToServiceRequest(model -> Translator.createDbInstanceRequest(model, tagSet))
                .backoffDelay(config.getBackoff())
                .makeServiceCall((createRequest, proxyInvocation) -> proxyInvocation.injectCredentialsAndInvokeV2(
                        createRequest,
                        proxyInvocation.client()::createDBInstance
                ))
                .stabilize((request, response, proxyInvocation, model, context) ->
                        isDBInstanceStabilizedAfterMutate(proxyInvocation, model))
                .handleError((request, exception, client, model, context) -> Commons.handleException(
                        ProgressEvent.progress(model, context),
                        exception,
                        CREATE_DB_INSTANCE_ERROR_RULE_SET
                ))
                .progress();
    }

    private ProgressEvent<ResourceModel, CallbackContext> restoreDbInstanceFromSnapshotV12(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress,
            final Tagging.TagSet tagSet
    ) {
        return proxy.initiate(
                        "rds::restore-db-instance-from-snapshot-v12",
                        rdsProxyClient,
                        progress.getResourceModel(),
                        progress.getCallbackContext()
                ).translateToServiceRequest(Translator::restoreDbInstanceFromSnapshotRequestV12)
                .backoffDelay(config.getBackoff())
                .makeServiceCall((restoreRequest, proxyInvocation) -> proxyInvocation.injectCredentialsAndInvokeV2(
                        restoreRequest,
                        proxyInvocation.client()::restoreDBInstanceFromDBSnapshot
                ))
                .stabilize((request, response, proxyInvocation, model, context) ->
                        isDBInstanceStabilizedAfterMutate(proxyInvocation, model))
                .handleError((request, exception, client, model, context) -> Commons.handleException(
                        ProgressEvent.progress(model, context),
                        exception,
                        RESTORE_DB_INSTANCE_ERROR_RULE_SET
                ))
                .progress();
    }

    private ProgressEvent<ResourceModel, CallbackContext> restoreDbInstanceFromSnapshot(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress,
            final Tagging.TagSet tagSet
    ) {
        return proxy.initiate(
                        "rds::restore-db-instance-from-snapshot",
                        rdsProxyClient,
                        progress.getResourceModel(),
                        progress.getCallbackContext()
                ).translateToServiceRequest(model -> Translator.restoreDbInstanceFromSnapshotRequest(model, tagSet))
                .backoffDelay(config.getBackoff())
                .makeServiceCall((restoreRequest, proxyInvocation) -> proxyInvocation.injectCredentialsAndInvokeV2(
                        restoreRequest,
                        proxyInvocation.client()::restoreDBInstanceFromDBSnapshot
                ))
                .stabilize((request, response, proxyInvocation, model, context) ->
                        isDBInstanceStabilizedAfterMutate(proxyInvocation, model))
                .handleError((request, exception, client, model, context) -> Commons.handleException(
                        ProgressEvent.progress(model, context),
                        exception,
                        RESTORE_DB_INSTANCE_ERROR_RULE_SET
                ))
                .progress();
    }

    private ProgressEvent<ResourceModel, CallbackContext> createDbInstanceReadReplica(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress,
            final Tagging.TagSet tagSet
    ) {
        return proxy.initiate(
                        "rds::create-db-instance-read-replica",
                        rdsProxyClient,
                        progress.getResourceModel(),
                        progress.getCallbackContext()
                ).translateToServiceRequest(model -> Translator.createDbInstanceReadReplicaRequest(model, tagSet))
                .backoffDelay(config.getBackoff())
                .makeServiceCall((createRequest, proxyInvocation) -> proxyInvocation.injectCredentialsAndInvokeV2(
                        createRequest,
                        proxyInvocation.client()::createDBInstanceReadReplica
                ))
                .stabilize((request, response, proxyInvocation, model, context) ->
                        isDBInstanceStabilizedAfterMutate(proxyInvocation, model))
                .handleError((request, exception, client, model, context) -> Commons.handleException(
                        ProgressEvent.progress(model, context),
                        exception,
                        CREATE_DB_INSTANCE_READ_REPLICA_ERROR_RULE_SET
                ))
                .progress();
    }

    protected ProgressEvent<ResourceModel, CallbackContext> updateDbInstanceAfterCreateV12(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        return proxy.initiate("rds::modify-db-instance-v12", rdsProxyClient, progress.getResourceModel(), progress.getCallbackContext())
                .translateToServiceRequest(resourceModel -> Translator.modifyDbInstanceAfterCreateRequestV12(request.getDesiredResourceState()))
                .backoffDelay(config.getBackoff())
                .makeServiceCall((modifyRequest, proxyInvocation) -> proxyInvocation.injectCredentialsAndInvokeV2(
                        modifyRequest,
                        proxyInvocation.client()::modifyDBInstance
                ))
                .stabilize((modifyRequest, response, proxyInvocation, model, context) -> isDBInstanceStabilizedAfterMutate(proxyInvocation, model))
                .handleError((modifyRequest, exception, client, model, context) -> Commons.handleException(
                        ProgressEvent.progress(model, context),
                        exception,
                        MODIFY_DB_INSTANCE_ERROR_RULE_SET
                ))
                .progress();
    }

    protected ProgressEvent<ResourceModel, CallbackContext> updateDbInstanceAfterCreate(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        return proxy.initiate("rds::modify-db-instance", rdsProxyClient, progress.getResourceModel(), progress.getCallbackContext())
                .translateToServiceRequest(resourceModel -> Translator.modifyDbInstanceAfterCreateRequest(request.getDesiredResourceState()))
                .backoffDelay(config.getBackoff())
                .makeServiceCall((modifyRequest, proxyInvocation) -> proxyInvocation.injectCredentialsAndInvokeV2(
                        modifyRequest,
                        proxyInvocation.client()::modifyDBInstance
                ))
                .stabilize((modifyRequest, response, proxyInvocation, model, context) -> isDBInstanceStabilizedAfterMutate(proxyInvocation, model))
                .handleError((modifyRequest, exception, client, model, context) -> Commons.handleException(
                        ProgressEvent.progress(model, context),
                        exception,
                        MODIFY_DB_INSTANCE_ERROR_RULE_SET
                ))
                .progress();
    }
}
