
/************************************

ecsTask (
  action: 'run',
  taskDefinition: 'example-task-definition',
  cluster: 'example-cluster',
  count: '1',
  startedBy: 'example-test-abc123',
  region: 'us-east-1',
  accountId: '12345678',
  role: 'ciinabox'
)
************************************/

@Grab(group='com.amazonaws', module='aws-java-sdk-ecs', version='1.11.359')
@Grab(group='com.amazonaws', module='aws-java-sdk-iam', version='1.11.359')
@Grab(group='com.amazonaws', module='aws-java-sdk-sts', version='1.11.359')

import com.amazonaws.auth.*
import com.amazonaws.regions.*
import com.amazonaws.services.ecs.*
import com.amazonaws.services.ecs.model.*
import com.amazonaws.services.securitytoken.*
import com.amazonaws.services.securitytoken.model.*
import com.amazonaws.waiters.*

import java.util.concurrent.*


def call(body) {
  def config = body
  def client = setupECSClient(config.region, config.accountId, config.role)

  // TODO: validate parameters
  config.count = config.count.toInteger()

  handleActionRequest(client, config)
}

//@NonCPS
def handleActionRequest(client, config) {
  def success = true

  switch(config.action) {
    case 'run':
      if (config.count < 1) {
        throw new GroovyRuntimeException("Running a task requires a positive value for 'count'.")
      }
      if (runTask(client, config)) {
        success = wait(client, config)
      }
      // TODO: check if service exists. if it does, just wait for it.
    case 'stop':
      stopTask(client, config)
      break
    default:
      throw new GroovyRuntimeException("The specified action '${config.action}' is not implemented.")
  }
  if(!success) {
    throw new Exception("Service '${config.service}' failed to ${config.action}.")
  }
}

//@NonCPS
def runTask(client, config) {
  def request = new RunTaskRequest()
    .withCluster(config.cluster)
    .withTaskDefinition(config.taskDefinition)
    .withCount(config.count)
    .withStartedBy(config.startedBy)

  try {
    RunTaskResult result = client.runTask(request)
    println """Successfully started ${config.count} tasks."
Task definition: ${config.taskDefinition}
Cluster: ${config.cluster}
StartedBy: ${config.startedBy}
Task ARNs: ${result.getTasks()}
"""
    env.TASKS = result.getTasks()    // TODO: do we have to join() this?
  } catch(AmazonECSException ex) {
    throw ex
  }
  return false
}

//@NonCPS
def stopTask(client, config) {
  def taskCount = env.TASKS.size()
  def success = true

  env.TASKS.eachWithIndex { task, i ->
    def request = StopTaskRequest()
    .withCluster(config.cluster)
    .withTask(task.getTaskArn())
    .withReason("Stopped by Jenkins.")

    try {
      StopTaskResult result = client.stopTask(request)
      println """Successfully stopped ${i}/${taskCount} tasks.
      """
    } catch(AmazonECSException e) {
      println "ERROR: task '${task.getTaskArn()}' failed to stop -- ${e}"
      success = false
    }
  }
  return success
}


//@NonCPS
def wait(client, config) {
  def waiter = client.waiters().tasksRunning()
  def desiredState = 'RUNNING'
  def timeout = 0
  def seconds = 5

  // ECS is eventually consistent, so give the tasks time to start
  println "Waiting for task to become ${desiredState}. Sleeping for ${seconds} seconds..."
  Thread.sleep(seconds * 1000)
  ListTasksResult taskList = client.listTasks(new ListTasksRequest().withCluster(config.cluster).withFamily(config.taskDefinition).withStartedBy(config.startedBy))
  DescribeTasksRequest result = new DescribeTasksRequest().withCluster(config.cluster).withTasks(taskList.getTaskArns())

  try {
    Future future = waiter.runAsync(
      new WaiterParameters<>(new DescribeTasksRequest().withCluster(config.cluster).withTasks(taskList.getTaskArns())),
      new NoOpWaiterHandler()
    )

    while(!future.isDone()) {
      try {
        DescribeTasksResult tasks = client.describeTasks(new DescribeTasksRequest().withCluster(config.cluster).withTasks(taskList.getTaskArns()))

        for (Task task: tasks.getTasks()) {
          println "Task: '${task.getTaskArn().split('/')[1]}', Status: ${task.getLastStatus()}"
          for (Container container: task.getContainers()) {
            println "Container: ${container.getName()}, Status: ${container.getLastStatus()}"
          }
        }
        println "Waiting for task to become ${desiredState}. Sleeping for ${seconds} seconds..."
        Thread.sleep(seconds * 1000)
      } catch(InterruptedException ex) {

      }
    }
  } catch(Exception e) {
     println "Task failed to become ${desiredState} - ${e}"
     return false
   }
   return true
}

//@NonCPS
def setupECSClient(region, awsAccountId = null, role = null) {
  def cb = AmazonECSClientBuilder.standard().withRegion(region)
  def creds = getCredentials(awsAccountId, region, role)
  if(creds != null) {
    cb.withCredentials(new AWSStaticCredentialsProvider(creds))
  }
  return cb.build()
}

//@NonCPS
def getCredentials(awsAccountId, region, roleName) {
  def env = System.getenv()
  if(env['AWS_SESSION_TOKEN'] != null) {
    return new BasicSessionCredentials(
      env['AWS_ACCESS_KEY_ID'],
      env['AWS_SECRET_ACCESS_KEY'],
      env['AWS_SESSION_TOKEN']
    )
  } else if(awsAccountId != null && roleName != null) {
    def stsCreds = assumeRole(awsAccountId, region, roleName)
    return new BasicSessionCredentials(
      stsCreds.getAccessKeyId(),
      stsCreds.getSecretAccessKey(),
      stsCreds.getSessionToken()
    )
  } else {
    return null
  }
}

//@NonCPS
def assumeRole(awsAccountId, region, roleName) {
  def roleArn = "arn:aws:iam::" + awsAccountId + ":role/" + roleName
  def roleSessionName = "sts-session-" + awsAccountId
  println "assuming IAM role ${roleArn}"
  def sts = new AWSSecurityTokenServiceClient()
  if (!region.equals("us-east-1")) {
      sts.setEndpoint("sts." + region + ".amazonaws.com")
  }
  def assumeRoleResult = sts.assumeRole(new AssumeRoleRequest()
            .withRoleArn(roleArn).withDurationSeconds(3600)
            .withRoleSessionName(roleSessionName))
  return assumeRoleResult.getCredentials()
}

