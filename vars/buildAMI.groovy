/***********************************

buildAMI(
  bakeVolumeSize: '30',
  bucketRegion: 'ap-southeast-2',
  chefPath: 'chef',
  chefRunList: 'recipe[mycookbook::default]',
  client: 'base2',
  cookbookVersion: '',
  copyAMIToRegions: ['us-west-2', 'us-east-2'],
  customVariables: [
    'variable_1': 'value_1',
    'variable_2': 'value_2'
  ],
  debug: true,
  instanceType: 'm4.large',
  packerTemplate: [
    name: 'packer/amz_ebs_ami.json',
    repo: 'github.com/rererecursive/ciinabox-bakery',
    branch: 'master'
  ],
  platform: 'windows',
  region: 'ap-southeast-2',
  role: 'bastion',
  shareAMIWith: ['12345678', '87654321'],
  shutdownTimeout: '60 minutes',
  skipCookbooks: true,
  sourceAMIId: 'ami-123456789',           # Or below; specify name and owner
  sourceAMIName: 'amzn-ami-hvm-2017.03.*',
  sourceAMIOwner: env.BASE_AMI_OWNER,
  sourceBucket: 'source.tools.example.com',
  sshUsername: env.SSH_USERNAME,
  customTags: [

  ]
)

***********************************/

// TODO: customTags
// TODO: support custom variables
// TODO: add tag for the OS version (platform)

import groovy.json.JsonOutput

def call(body) {
  def config = body

  upgradePacker()     // TODO: TEMP!
  configurePackerTemplate(config)
  configureUserVariables(config)
  configureStackVariables(config)
  configureCookbooks(config)
  configureShutdown(config)
  bake(config)

}

// Prepare the template's user variables.
def configureUserVariables(config) {
  def packerConfig = [
    'ami_users':          config.get('shareAMIWith', '').join(','),
    'ami_regions':        config.get('copyAMIToRegions', '').join(','),
    'bake_volume_size':   config.bakeVolumeSize,
    'bucket_region':      config.bucketRegion,
    'build_no':           env.BUILD_NUMBER,
    'chef_path':          config.chefPath,
    'chef_repo_branch':   env.BRANCH_NAME,
    'chef_repo_commit':   env.GIT_COMMIT.substring(0, 7),
    'chef_run_list':      config.chefRunList,
    'client':             config.client,
    'cookbook_version':   config.cookbookVersion,
    'instance_type':      config.instanceType,
    'platform':           config.platform,
    'region':             config.region,
    'role':               config.role,
    'source_ami':         config.sourceAMIId,
    'source_ami_name':    config.sourceAMIName,
    'source_ami_owner':   config.sourceAMIOwner,
    'source_bucket':      config.sourceBucket,
    'ssh_username':       config.sshUsername
  ]

  if (config.debug == true) {
    config.debug = '-debug'
  } else {
    config.debug = ''
  }

  if (config.bucketRegion == null) {
    packerConfig['bucket_region'] = config.region
  }

  if (config.customVariables) {
    packerConfig = packerConfig + config.customVariables
  }

  config.userConfig = packerConfig
}

// Get the VPC configuration from the deployed stack.
def configureStackVariables(config) {
  withEnv(["REGION=${config.region}", "STACK=ciinabox"]) {
    println "Fetching variables from CloudFormation stack: ${env.STACK} ..."

    println "\nWriting to: vpc.json\n"

    sh '''#!/bin/bash
      eval `aws cloudformation describe-stacks --stack-name ${STACK} --query 'Stacks[*].Outputs[*].{Key:OutputKey, Value:OutputValue}' --region ${REGION} --output text | tr -s '\t' | tr '\t' '='`
      tee vpc.json <<EOF

{
  "vpc_id": "${VPCId}",
  "subnet_id": "${ECSPrivateSubnetA}",
  "security_group": "${SecurityGroup}",
  "packer_role": "${ECSRole}",
  "packer_instance_profile": "${ECSInstanceProfile}"
}

EOF
    '''
  }
}

// Fetch the template.
def configurePackerTemplate(config) {
  def repo = 'https://github.com/rererecursive/packer-templates'
  def branch = 'master'
  def template

  if (config.packerTemplate.getClass() == String) {
    template = config.packerTemplate
  }
  else {
    template = config.packerTemplate.get('name')
    repo = config.packerTemplate.get('repo', repo)
    branch = config.packerTemplate.get('branch', branch)
  }

  println "Cloning git repository: ${repo} with branch ${branch} ..."
  git(branch: branch, url: repo)

  //config.packerTemplate = 'templates/' + template
  config.packerTemplate = template
}

// Configure Chef cookbooks. They may be stashed from a previous pipeline step.
def configureCookbooks(config) {
  sh 'mkdir -p data_bags environments encrypted_data_bag_secret'
  sh 'rm -rf cookbooks'

  if (config.skipCookbooks) {
    sh "mkdir -p cookbooks"
  } else {
    unstash 'cookbook'
    sh 'tar xfz cookbooks.tar.gz'
  }
}

def configureShutdown(config) {
  if (config.shutdownTimeout) {
    def (timeout, type) = config.shutdownTimeout.split()

    if (type.startsWith('hour')) {
      timeout = timeout.toInteger() * 60
    }

    println "Set the shutdown timeout for Packer to ${config.shutdownTimeout}."
    config.userConfig['shutdown_timeout'] = timeout.toString()
  }
}

// Upgrade Packer to 1.3.4 to take advantage of newer features (e.g. access to source AMI tags)
def upgradePacker() {
  sh "wget -q https://releases.hashicorp.com/packer/1.3.4/packer_1.3.4_linux_amd64.zip && unzip -o packer_1.3.4_linux_amd64.zip && sudo mv packer /opt/packer/packer && rm -rf packer*"
}

// Build the AMI.
def bake(config) {
  println "\nWriting to user.json\n"
  writeFile(file: 'user.json', text: JsonOutput.prettyPrint(JsonOutput.toJson(config.userConfig)))

  // Remove any empty values from the variables file and default to the values in the template.
  sh "cat user.json"
  println "\nRemoving variables that have empty values...\n"
  sh "jq -s add user.json vpc.json > temp.json"
  sh "jq 'with_entries( select( .value != null and .value != \"\" ) )' temp.json > variables.json"

  sh "cat variables.json"
  sh "ls -al"

  sh "/opt/packer/packer version"
  sh "/opt/packer/packer validate -var-file=variables.json ${config.packerTemplate}"
  sh "/opt/packer/packer build -machine-readable ${config.debug} -var-file=variables.json ${config.packerTemplate}"

  println "\nProduced artifacts:\n"
  sh "cat builds.json"  // Produced by Packer
}
