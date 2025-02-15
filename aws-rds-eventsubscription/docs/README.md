# AWS::RDS::EventSubscription

The AWS::RDS::EventSubscription resource allows you to receive notifications for Amazon Relational Database Service events through the Amazon Simple Notification Service (Amazon SNS). For more information, see Using Amazon RDS Event Notification in the Amazon RDS User Guide.

## Syntax

To declare this entity in your AWS CloudFormation template, use the following syntax:

### JSON

<pre>
{
    "Type" : "AWS::RDS::EventSubscription",
    "Properties" : {
        "<a href="#tags" title="Tags">Tags</a>" : <i>[ <a href="tag.md">Tag</a>, ... ]</i>,
        "<a href="#subscriptionname" title="SubscriptionName">SubscriptionName</a>" : <i>String</i>,
        "<a href="#enabled" title="Enabled">Enabled</a>" : <i>Boolean</i>,
        "<a href="#eventcategories" title="EventCategories">EventCategories</a>" : <i>[ String, ... ]</i>,
        "<a href="#snstopicarn" title="SnsTopicArn">SnsTopicArn</a>" : <i>String</i>,
        "<a href="#sourceids" title="SourceIds">SourceIds</a>" : <i>[ String, ... ]</i>,
        "<a href="#sourcetype" title="SourceType">SourceType</a>" : <i>String</i>
    }
}
</pre>

### YAML

<pre>
Type: AWS::RDS::EventSubscription
Properties:
    <a href="#tags" title="Tags">Tags</a>: <i>
      - <a href="tag.md">Tag</a></i>
    <a href="#subscriptionname" title="SubscriptionName">SubscriptionName</a>: <i>String</i>
    <a href="#enabled" title="Enabled">Enabled</a>: <i>Boolean</i>
    <a href="#eventcategories" title="EventCategories">EventCategories</a>: <i>
      - String</i>
    <a href="#snstopicarn" title="SnsTopicArn">SnsTopicArn</a>: <i>String</i>
    <a href="#sourceids" title="SourceIds">SourceIds</a>: <i>
      - String</i>
    <a href="#sourcetype" title="SourceType">SourceType</a>: <i>String</i>
</pre>

## Properties

#### Tags

An array of key-value pairs to apply to this resource.

_Required_: No

_Type_: List of <a href="tag.md">Tag</a>

_Update requires_: [No interruption](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/using-cfn-updating-stacks-update-behaviors.html#update-no-interrupt)

#### SubscriptionName

The name of the subscription.

_Required_: No

_Type_: String

_Maximum_: <code>255</code>

_Update requires_: [Replacement](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/using-cfn-updating-stacks-update-behaviors.html#update-replacement)

#### Enabled

A Boolean value; set to true to activate the subscription, set to false to create the subscription but not active it.

_Required_: No

_Type_: Boolean

_Update requires_: [No interruption](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/using-cfn-updating-stacks-update-behaviors.html#update-no-interrupt)

#### EventCategories

A list of event categories for a SourceType that you want to subscribe to. You can see a list of the categories for a given SourceType in the Events topic in the Amazon RDS User Guide or by using the DescribeEventCategories action.

_Required_: No

_Type_: List of String

_Update requires_: [No interruption](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/using-cfn-updating-stacks-update-behaviors.html#update-no-interrupt)

#### SnsTopicArn

The Amazon Resource Name (ARN) of the SNS topic created for event notification. The ARN is created by Amazon SNS when you create a topic and subscribe to it.

_Required_: Yes

_Type_: String

_Update requires_: [Replacement](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/using-cfn-updating-stacks-update-behaviors.html#update-replacement)

#### SourceIds

The list of identifiers of the event sources for which events will be returned. If not specified, then all sources are included in the response. An identifier must begin with a letter and must contain only ASCII letters, digits, and hyphens; it cannot end with a hyphen or contain two consecutive hyphens.

_Required_: No

_Type_: List of String

_Update requires_: [No interruption](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/using-cfn-updating-stacks-update-behaviors.html#update-no-interrupt)

#### SourceType

The type of source that will be generating the events. For example, if you want to be notified of events generated by a DB instance, you would set this parameter to db-instance. if this value is not specified, all events are returned.

_Required_: No

_Type_: String

_Update requires_: [No interruption](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/using-cfn-updating-stacks-update-behaviors.html#update-no-interrupt)

## Return Values

### Ref

When you pass the logical ID of this resource to the intrinsic `Ref` function, Ref returns the SubscriptionName.
