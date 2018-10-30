/***********************************
 tagAMI DSL

 Tags an AMI

 example usage
  tagAMI(
    region: 'ap-southeast-2',             # Required
    tags: ['Status':'Verified'],          # Required
    ami: 'xyz',                           # Optional
    accounts: ['111111111','222222222'],  # Optional
    role: 'ciinabox'                      # Optional
  )
 ************************************/
 @Grab(group='com.amazonaws', module='aws-java-sdk-ec2', version='1.11.198')

import com.amazonaws.services.ec2.*
import com.amazonaws.services.ec2.model.*
import com.amazonaws.regions.*

def call(body) {
  def config = body
  def config.role = config.get('role', 'ciinabox')
  def config.accounts = config.get('accounts', [])
  def client = setupEC2Client(config.region)

  node {
    println config
    if(config.ami) {
      addTags(client, config.region, config.ami, config.tags)

      config.accounts.each { accountId ->
        client = setupEC2Client(config.region, accountId, config.role)
        addTags(client, config.region, config.ami, config.tags, accountId)
      }
    } else {
      //Hack to read ALL env vars
      println "No AMI was specified. Collecting baked AMIs..."
      envVars = [:]
      sh 'env > env.txt'
      readFile('env.txt').split("\r?\n").each {
        e = it.split('=')
        envVars[e[0]] = e[1]
      }
      amis = findAMIsInEnvironment(envVars)
      println "Found ${amis.size()} baked AMIs to tag: ${amis}"
      amis.each { ami ->
        addTags(client, config.region, ami, config.tags)
      }
    }
  }
}

@NonCPS
def addTags(client, region, ami, tags, accountId = null) {
  if (accountId) {
    println "Adding tags to ami: ${ami} in account ${accountId}"
  }
  else {
    println "Adding tags to ami: ${ami}"
  }


  def newTags = []
  tags.each { key, value ->
    newTags << new Tag(key, value)
  }

  client.createTags(new CreateTagsRequest()
    .withResources(ami)
    .withTags(newTags)
  )
}

@NonCPS
def findAMIsInEnvironment(environment) {
  def amis = []
  environment.each { name, value ->
    if(name.endsWith('_BAKED_AMI')) {
      amis << value
    }
  }
  return amis
}

// TODO: put the below functions in a helper class
@NonCPS
def setupEC2Client(region, awsAccountId = null, role = null) {
  def cb = AmazonEC2ClientBuilder.standard().withRegion(region)
  def creds = getCredentials(awsAccountId, region, role)
  if(creds != null) {
    cb.withCredentials(new AWSStaticCredentialsProvider(creds))
  }
  return cb.build()
}

@NonCPS
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

@NonCPS
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
