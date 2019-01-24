/***********************************

bakeAMI(
  region: env.REGION,
  role: 'MyServer',
  baseAMI: 'amzn-ami-hvm-2017.03.*',
  owner: env.BASE_AMI_OWNER,          # Or below
  baseAMIId: 'ami-123456789',         # Or above
  bakeAMIType: 'm4.large',
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
    'instance_type':      config.bakeAMIType,
    'packer_template':    config.packerTemplate,
    'region':             config.region,
    'role':               config.role,
    'source_ami':         config.baseAMIId,
    'source_ami_name':    config.baseAMI,
    'source_ami_owner':   config.owner,
    'ssh_username':       config.sshUsername
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
  def templateRepo = 'https://github.com/rererecursive/ciinabox-bakery'

  if (config.packerTemplateRepo) {
    templateRepo = config.templateRepo
  }

  dir('templates') {
    git(branch: 'master', url: templateRepo)
  }

  config.packerTemplate = 'templates/' + config.packerTemplate
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
  sh "sed -i 's/null/\"\"/g' variables.json"

  sh "cat variables.json"
  sh "cat ${config.template.path}"

  sh "/opt/packer/packer version"
  sh "/opt/packer/packer validate -var-file variables.json ${config.packerTemplateRepo}"
  sh "/opt/packer/packer build -machine-readable -var-file=variables.json ${config.packerTemplateRepo} ${config.debug}"
}
