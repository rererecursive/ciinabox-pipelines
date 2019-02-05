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
  sshUsername: env.SSH_USERNAME
)

***********************************/

import groovy.json.JsonOutput

def call(body) {
  def config = body

  configureUserVariables(config)
  configureStackVariables(config)
  configurePackerTemplate(config)
  configureCookbooks(config)
  configureShutdown()
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
    'chef_run_list':      config.bakeChefRunList,
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
  deleteDir()
  withEnv(["REGION=${config.region}", "STACK=ciinabox"]) {
    println "Fetching variables from CloudFormation stack: ${env.STACK} ..."

    sh '''#!/bin/bash
      eval `aws cloudformation describe-stacks --stack-name ${STACK} --query 'Stacks[*].Outputs[*].{Key:OutputKey, Value:OutputValue}' --region ${REGION} --output text | tr -s '\t' | tr '\t' '='`
      echo "\nWriting to: vpc.json"
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
  if (config.packerTemplate.getClass() == String) {
    // Convert it to a map to make future calculations simpler
    config.packerTemplate = ['name': config.packerTemplate]
  }

  def repo = config.packerTemplate.get('repo', 'https://github.com/rererecursive/packer-templates')
  def branch = config.packerTemplate.get('branch', 'master')

  println "Cloning git repository: ${repo} with branch ${branch} ..."
  sh 'mkdir -p templates'
  dir('templates') {
    git(branch: branch, url: repo)
  }

  config.packerTemplate = 'templates/' + config.packerTemplate
}

// Configure Chef cookbooks. They may be stashed from a previous pipeline step.
def configureCookbooks(config) {
  sh 'mkdir -p data_bags environments encrypted_data_bag_secret'

  if (config.skipCookbooks) {
    sh "mkdir -p cookbooks"
  } else {
    unstash 'cookbook'
    sh 'tar xvfz cookbooks.tar.gz'
  }
}

def configureShutdown(config) {
  if (config.shutdownTimeout) {
    def timeout, type = config.shutdownTimeout.split()

    if (type.startsWith('hour')) {
      timeout = timeout.toInteger() * 60
    }

    echo "Set the shutdown timeout for Packer to ${config.shutdownTimeout}."
    config.userConfig['shutdown_timeout'] = timeout.toString()
  }
}

// Build the AMI.
def bake(config) {
  echo "\nWriting to user.json\n"
  writeFile(file: 'user.json', text: JsonOutput.prettyPrint(JsonOutput.toJson(config.userConfig)))

  // Remove any empty values from the variables file and default to the values in the template.
  sh "cat user.json"
  echo "\nRemoving empty values from variables\n"
  sh "jq -s add user.json vpc.json > temp.json"
  sh "jq 'with_entries( select( .value != null and .value != \"\" ) )' temp.json > variables.json"

  sh "cat variables.json"

  sh "/opt/packer/packer version"
  sh "/opt/packer/packer validate -var-file=variables.json ${config.packerTemplate}"
  sh "/opt/packer/packer build -machine-readable -var-file=variables.json ${config.packerTemplate} ${config.debug}"

  echo "\nProduced artifacts:\n"
  sh "cat builds.json"  // Produced by Packer
}
