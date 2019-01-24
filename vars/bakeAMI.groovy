/***********************************

bakeAMI(
  app: 'bastion',
  sourceAMI: [
    name: 'amzn-ami-hvm-2018.03.*',   # Or 'id'
    id: 'ami-12345678',               # Or 'name'
    owner: '12345678'
  ],
  region: '',
  shareAmiWith: ['', ''],
  withCookbooks: true,
  chefRunList: '',
  variables: [

  ],
  template: [
    owner: 'base2',
    path: 'templates/amazon-linux/file.json'
  ],
  debug: true
)

**********/

def call(body) {
  def config = body

  configureUserVariables(config)
  configureStackVariables(config)
  configurePackerTemplate(config)
  confgureCookbooks()
  bake()

}

// Prepare the user template variables.
def configureUserVariables(config) {
  def packerConfig = [
    'app':                config.app,
    'ami_users':          config.shareAmiWith,
    'build_no':           env.BUILD_NUMBER,
    'chef_repo_branch':   env.BRANCH_NAME,
    'chef_repo_commit':   env.GIT_COMMIT.substring(0, 7),
    'chef_run_list':      config.chefRunList,
    'packer_template':    config.template.name,
    'region':             config.region,
    'source_ami':         config.sourceAMI.id,
    'source_ami_name':    config.sourceAMI.name,
    'source_ami_owner':   config.sourceAMI.owner
  ]

  if (config.debug = true) {
    config.debug = '-debug'
  } else {
    config.debug = ''
  }

  config.packerConfig = packerConfig
}

// Get the VPC configuration from the deployed stack.
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
def configurePackerTemplate(config) {
  if (config.template.owner == 'base2') {
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
      tee filter.json <<EOF

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
def configureCookbooks(config) {
  if (config.skipCookbooks) {
    sh "mkdir -p cookbooks"
  } else {
    unstash 'cookbook'
    sh 'tar xvfz cookbooks.tar.gz'
  }
}

// Build the AMI.
def bake(config) {
  writeJSON(file: 'user.json', json: config.packerConfig, pretty: 2)
  sh "jq -s add user.json vpc.json > variables.json"

  sh "cat variables.json"
  sh "cat ${config.template.path}"

  sh "/opt/packer/packer version"
  sh "/opt/packer/packer validate -var-file variables.json ${config.template.path}"
  sh "/opt/packer/packer build -machine-readable -var-file=variables.json ${config.template.path} ${config.debug}"
}
