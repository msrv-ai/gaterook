package ai.msrv.gaterook.connectors.aws;

import java.util.Set;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;

/**
 * The operations the Cloud Control and CloudWatch clients may run (report 09 §1 layer 1, §7): the five reads of
 * {@code aws_describe} and {@code aws_metrics}. Anything else — {@code DeleteResource}, {@code UpdateResource},
 * {@code PutMetricData}, {@code SetAlarmState} — fails with a {@link SecurityException} before the request is signed
 * or sent, whatever the code above it asks for. Defence in depth: the connector has no path to those calls.
 */
final class ReadOnlyGuard implements ExecutionInterceptor {

  static final Set<String> OPERATIONS = Set.of("ListResources", "GetResource", "GetMetricData", "ListMetrics",
      "DescribeAlarms");

  @Override
  public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
    String operation = attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);
    if (!OPERATIONS.contains(operation)) {
      throw new SecurityException("Blocked " + attributes.getAttribute(SdkExecutionAttribute.SERVICE_NAME) + "."
          + operation + ": the AWS connector only reads");
    }
  }
}
