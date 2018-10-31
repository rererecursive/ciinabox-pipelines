/***********************************
 lookupAMI DSL

 Looks up an AMI to

 example usage
 lookupAMI(
    region: 'ap-southeast-2',     // Required
    amiName: 'xyz',               // Required
    amiBranch: 'master',          // Optional
    tags: ['Status':'Verified'],  // Optional
    owner: '12345678'             // Optional
  )
 ************************************/
@Grab(group='com.amazonaws', module='aws-java-sdk-ec2', version='1.11.198')
@Grab(group='com.amazonaws', module='aws-java-sdk-sts', version='1.11.198')

import com.amazonaws.services.ec2.*
import com.amazonaws.services.ec2.model.*
import com.amazonaws.regions.*
import com.amazonaws.services.securitytoken.*
import com.amazonaws.services.securitytoken.model.*

def call(body) {
  def config = body

  if(!config['owner']) {
    config.owner = lookupAccountId()
  }
  println "Looking up AMIs with config: ${config}"
  def image = lookupAMI(config)
  if(image) {
    println "Details for image '${image.name}': ${image}"
    env["SOURCE_AMI"] = image.imageId
    env["SOURCE_AMI_NAME"] = image.name
    return image.imageId
  } else {
    println "ERROR: no AMI was found for config: ${config}"
    return null
  }
}

def lookupAMI(config) {
  def ec2 = AmazonEC2ClientBuilder.standard()
    .withRegion(config.region)
    .build()
  def image = null
  def filters = []

  filters << new Filter().withName('name').withValues(config.amiName)
  filters << new Filter().withName('root-device-type').withValues('ebs')

  if(config['tags']) {
    config.tags.each { key, value ->
      filters << new Filter("tag:${key}").withValues("${value}")
    }
  }

  if(config.amiBranch) {
    println "Filtering AMIs for tag 'BranchName=${config.amiBranch}'..."
    filters << new Filter("tag:BranchName").withValues("${config.amiBranch}")
  }

  def imagesList = ec2.describeImages(new DescribeImagesRequest()
    .withOwners([config.owner])
    .withFilters(filters)
  )

  if(imagesList.images.size() > 0) {
    def images = imagesList.images.collect()
    println "Found ${images.size()} AMIs: ${images.collect { it.name }.sort().reverse()}"
    image = images.get(findNewestImage(images))
    println "Found AMI '${image.name}' (${image.imageId})."
  }
  return image
}

def findNewestImage(images) {
  def index = 0
  def newest = Date.parse("yyyy-MM-dd", "2000-01-01")
  def found = 0

  println "Filtering AMIs for the newest image..."
  images.each { image ->
    imageDate = Date.parse("yyyy-MM-dd'T'HH:mm:ss", image.creationDate)
    if(imageDate >= newest) {
      found = index
      newest = imageDate
    }
    index++
  }
  return found
}

def lookupAccountId() {
  def sts = AWSSecurityTokenServiceClientBuilder.standard()
    .withRegion(Regions.AP_SOUTHEAST_2)
    .build()
  return sts.getCallerIdentity(new GetCallerIdentityRequest()).account
}
