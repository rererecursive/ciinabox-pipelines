/***********************************

bakeAMI(
  region: env.REGION,
  role: 'MyServer',
  baseAMI: 'amzn-ami-hvm-2017.03.*',
  owner: env.BASE_AMI_OWNER,          # Or below
  baseAmiId: 'ami-123456789',         # Or above
  bakeChefRunList: 'recipe[mycookbook::default]',
  client: env.CLIENT,
  shareAmiWith: env.SHARE_AMI_WITH,
  packerTemplate: 'packer/amz_ebs_ami.json'
  amiBuildNumber: env.AMI_BUILD_NUMBER,
  sshUsername: env.SSH_USERNAME,
  debug: true
)

***********************************/

def call(body) {
  def config = body

  configureUserVariables(config)
  configureStackVariables(config)
  configurePackerTemplate(config)
  confgureCookbooks(config)
  bake(config)

}

// Prepare the user template variables.
@NonCPS
def configureUserVariables(config) {
  def packerConfig = [
    'ami_users':          config.shareAmiWith,
    'chef_repo_branch':   env.BRANCH_NAME,
    'chef_repo_commit':   env.GIT_COMMIT.substring(0, 7),
    'chef_run_list':      config.bakeChefRunList,
    'client':             config.client,
    'packer_template':    config.packerTemplate,
    'region':             config.region,
    'role':               config.role,
    'source_ami':         config.baseAmiId,
    'source_ami_name':    config.baseAMI,
    'source_ami_owner':   config.owner
  ]

  if (config.debug = true) {
    config.debug = '-debug'
  } else {
    config.debug = ''
  }

  if (config.amiBuildNumber) {
    packerConfig['build_no'] = config.amiBuildNumber
  } else {
    packerConfig['build_no'] = env.BUILD_NUMBER
  }

  if (config.customVariables) {
    packerConfig = packerConfig + config.customVariables
  }

  config.packerConfig = packerConfig
}

// Get the VPC configuration from the deployed stack.
@NonCPS
def configureStackVariables(config) {
  sh """#!/bin/bash
    eval `aws cloudformation describe-stacks --stack-name ciinabox --query 'Stacks[*].Outputs[*].{Key:OutputKey, Value:OutputValue}' --region ${config.region} --output text | tr -s '\t' | tr '\t' '='`
    tee vpc.json <<EOF

{
  "vpc_id": "${VPCId}",
  "subnet_id": "${ECSPrivateSubnetA}",
  "security_group": "${SecurityGroup}",
  "packer_role": "${ECSRole}",
  "packer_instance_profile": "${ECSInstanceProfile}",
}

    EOF
  """
}

// Fetch the template.
@NonCPS
def configurePackerTemplate(config) {
  if (config.template.owner == null || config.template.owner == 'base2') {
    dir('base2') {
      git(branch: 'master', url: 'https://github.com/rererecursive/ciinabox-bakery.git')
    }
    config.template.path = 'base2/' + config.template.path
  }

  // Configure the source AMI.
  if (config.sourceAMI.id) {
    config.packerConfig['source_ami'] = config.sourceAMI.id
  }
  else {
    // Rewrite the template to make Packer lookup an AMI.
    sh """#!/bin/bash
      tee filter.json <<ECSInstanceProfile

{
  "source_ami_filter": {
    "filters": {
      "virtualization-type": "hvm",
      "name": "{{ user `source_ami_name` }}",
      "root-device-type": "ebs"
    },
    "owners": ["{{ user `source_ami_owner` }}"],
    "most_recent": true
  }
}

      EOF
    """
    config.packerConfig['source_ami_name'] = config.sourceAMI.name
    config.packerConfig['source_ami_owner'] = config.sourceAMI.owner

    sh "jq '.builds[0] += `cat filter.json`' ${config.template.path} > tmp" // Add the filter to the template.
    sh "jq 'del(.builds[0].source_ami)' tmp > ${config.template.path}"      // Remove the AMI ID parameter.
  }
}

// Configure Chef cookbooks. They may be stashed from a previous pipeline step.
@NonCPS
def configureCookbooks(config) {
  if (config.skipCookbooks) {
    sh "mkdir -p cookbooks"
  } else {
    unstash 'cookbook'
    sh 'tar xvfz cookbooks.tar.gz'
  }
}

// Build the AMI.
@NonCPS
def bake(config) {
  writeJSON(file: 'user.json', json: config.packerConfig, pretty: 2)
  sh "jq -s add user.json vpc.json > variables.json"

  sh "cat variables.json"
  sh "cat ${config.template.path}"

  sh "/opt/packer/packer version"
  sh "/opt/packer/packer validate -var-file variables.json ${config.template.path}"
  sh "/opt/packer/packer build -machine-readable -var-file=variables.json ${config.template.path} ${config.debug}"
}
