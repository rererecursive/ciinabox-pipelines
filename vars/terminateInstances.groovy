/***********************************

Terminate instances based on their tags.

terminateInstances(
  tags: [
    'Name': 'Packer*',
    'BuildNumber': env.BUILD_NUMBER,
    'BuildSHA': env.GIT_COMMIT
  ],
  region: 'ap-southeast-2'
)
************************************/

@Grab(group='com.amazonaws', module='aws-java-sdk-ec2', version='1.11.359')

import com.amazonaws.services.ec2.*
import com.amazonaws.services.ec2.model.*

def call(config) {
  def region = config.region
  AmazonEC2 client = AmazonEC2ClientBuilder.standard().withRegion(region).build()

  def filters = []
  def instanceIds = []

  config.tags.each { k,v ->
    filters << new Filter("tag:${k}").withValues(v.toString())
  }

  println "Looking for instances with tags: ${config.tags} ..."

  DescribeInstancesResult result = client.describeInstances(new DescribeInstancesRequest().withFilters(filters))
  List<Reservation> reservations = result.getReservations()

  if (!reservations.size()) {
    println "Found 0 instances."
    return
  }

  println "Terminating the following instances:"

  reservations.each { reservation ->
    reservation.getInstances().each { instance ->
      def name = instance.getTags().findAll { it.key == 'Name' }[0].value
      def instanceId = instance.getInstanceId()

      println "\t${instanceId} - (${name})"
      instanceIds << instanceId
    }
  }

  client.terminateInstances(new TerminateInstancesRequest().withInstanceIds(instanceIds))

  println "A total of ${instanceIds.size()} instances were terminated."
}
