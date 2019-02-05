/***********************************
 cleanAMIs DSL

 Clean up old AMIs based on a date or the number of existing images with the same name

 example usage

 cleanAMIs(
  name: 'base2-bastion-*',
  olderThan: 60,
  keepLast: 100
)
 ************************************/
@Grab(group='com.amazonaws', module='aws-java-sdk-ec2', version='1.11.198')

import com.amazonaws.services.ec2.*
import com.amazonaws.services.ec2.model.*
import com.amazonaws.regions.*

def call(body) {
  def config = body
  config['amis'].each { ami ->
    cleanupAMI(config.region, ami)
  }
}

def cleanupAMI(region, ami){
  def ec2 = AmazonEC2ClientBuilder.standard()
    .withRegion(region)
    .build()

  def result = ec2.describeImages(new DescribeImagesRequest().withImageIds(ami))
  println result
  if (!result.getImages().isEmpty()) {
    println "Deregistering AMI ${ami}"
    ec2.deregisterImage(new DeregisterImageRequest(ami))
    for (BlockDeviceMapping blockingDevice : result.getImages().get(0).getBlockDeviceMappings()) {
      if (blockingDevice.getEbs() != null) {
        def snapshot = blockingDevice.getEbs().getSnapshotId()
        println "Deleting snapshot ${snapshot}"
        ec2.deleteSnapshot(new DeleteSnapshotRequest().withSnapshotId(snapshot))
      }
    }
  }
}
