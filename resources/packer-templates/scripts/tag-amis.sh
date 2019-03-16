#!/bin/bash

# Tag an AMI with its source AMI's build date.

if [[ $SOURCE_AMI == "" ]]; then
  echo "SOURCE_AMI is empty. Skipping additional artifacts tagging."
  exit 0
fi

SOURCE_AMI_BUILD_DATE=$(aws ec2 describe-images --image-ids $SOURCE_AMI --owner $SOURCE_AMI_OWNER --query "Images[0].CreationDate" --output text)

ARTIFACTS=$1
echo $ARTIFACTS | jq -r '.builds[] | (.artifact_id)' > builds.json

for build in $(cat builds.json); do
  REGION=$(echo $build | cut -d : -f 1)
  AMI=$(echo $build | cut -d : -f 2)

  echo "Tagging $AMI in region $REGION with its source AMI's build date: $SOURCE_AMI_BUILD_DATE"
  aws ec2 create-tags --resources $AMI --tags Key=SourceAMIBuildDate,Value=$SOURCE_AMI_BUILD_DATE  --region $REGION || true
done
