#!/bin/bash

AMI="$1"
echo "AMI=$AMI"
BROKER_VERSION="$2"
echo "BROKER_VERSION=$BROKER_VERSION"
CORE_COUNT="$3"
echo "CORE_COUNT=$CORE_COUNT"
FS="$4"
echo "FS=$FS"
INSTANCE="$5"
echo "INSTANCE=$INSTANCE"
KEY_PAIR="$6"
echo "KEY_PAIR=$KEY_PAIR"
NODE_NUMBER="$7"
echo "NODE_NUMBER=$NODE_NUMBER"
NODE_RANGE_END="$8"
echo "NODE_RANGE_END=$NODE_RANGE_END"
NODE_RANGE_START="$9"
echo "NODE_RANGE_START=$NODE_RANGE_START"
NODE_ROLE=${10}
echo "NODE_ROLE=$NODE_ROLE"
RUN_TAG="${11}"
echo "RUN_TAG=$RUN_TAG"
SG="${12}"
echo "SG=$SG"
SN="${13}"
echo "SN=$SN"
TENANCY="${14}"
echo "TENANCY=$TENANCY"
TPC="${15}"
echo "TPC=$TPC"
VARS_FILE="${16}"
echo "VARS_FILE=$VARS_FILE"
VOL_SIZE="${17}"
echo "VOL_SIZE=$VOL_SIZE"
VOL_TYPE="${18}"
echo "VOL_TYPE=$VOL_TYPE"

set -e

ROOT_PATH=$(pwd)

INFLUX_IP=$(aws ec2 describe-instances --filters "Name=tag:inventorygroup,Values=benchmarking_metrics" --query "Reservations[*].Instances[*].PrivateIpAddress" --output=text)
INFLUX_URL="http://$INFLUX_IP:8086"
echo "Node $NODE_NUMBER: InfluxDB url is $INFLUX_URL"

# build the list of hosts in the cluster
HOSTS="["
for NODE in $(seq $NODE_RANGE_START $NODE_RANGE_END)
do
    TAG="benchmarking_rabbitmq${NODE}_${RUN_TAG}"
    NODE_IP=$(aws ec2 describe-instances --filters "Name=tag:inventorygroup,Values=$TAG" --query "Reservations[*].Instances[*].PrivateIpAddress" --output=text)
    HOSTS="${HOSTS}{\"ip\":\"${NODE_IP}\",\"host\":\"rabbitmq${NODE}\"}"

    if [[ $NODE != $NODE_RANGE_END ]]; then
        HOSTS="${HOSTS},"
    fi
done
HOSTS="${HOSTS}]"

echo "Hosts is $HOSTS"

if (( $VOL_SIZE > 1999 )); then 
    VOLUME_SIZE_LABEL=$(($VOL_SIZE/1000))T
else
    VOLUME_SIZE_LABEL="${VOL_SIZE}G"
fi
echo "VOLUME_SIZE_LABEL is $VOLUME_SIZE_LABEL"

# provision server
echo "Node $NODE_NUMBER: Configuring rabbitmq EC2 instance $NODE_NUMBER with role of $NODE_ROLE"
cd $ROOT_PATH/rabbitmq

ansible-playbook install-rabbitmq.yml --private-key=~/.ssh/$KEY_PAIR.pem --ssh-common-args='-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null' \
--extra-vars "influx_url=$INFLUX_URL" \
--extra-vars "rabbitmq_version=${BROKER_VERSION}-1" \
--extra-vars "hostname=rabbitmq${NODE_NUMBER}" \
--extra-vars "filesystem=${FS}" \
--extra-vars "node=$NODE_NUMBER" \
--extra-vars "volume_size=$VOL_SIZE" \
--extra-vars "run_tag=$RUN_TAG" \
--extra-vars "volume_size_label=$VOLUME_SIZE_LABEL" \
--extra-vars "@${VARS_FILE}" \
--extra-vars "node_role=$NODE_ROLE" \
--extra-vars '{"rabbitmq_hosts":'"${HOSTS}"'}' \
--extra-vars "rabbitmq_cluster_master=rabbitmq${NODE_RANGE_START}"