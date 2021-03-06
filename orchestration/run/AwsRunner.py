import sys
import io
import subprocess
import threading
import time
import uuid
import os.path
from random import randint
from collections import namedtuple
from printer import console_out
from Runner import Runner

class AwsRunner(Runner):
    def __init__(self):
        super().__init__()

    def run_benchmark(self, unique_conf, common_conf, playlist_entry, policies, run_ordinal):
        status_id = unique_conf.technology + unique_conf.node_number

        nodes = ""
        for x in range(int(unique_conf.cluster_size)):
            comma = ","
            if x == 0:
                comma = ""

            node_number = int(unique_conf.node_number) + x
            nodes = f"{nodes}{comma}rabbit@rabbitmq{node_number}"

        federation_args = ""
        if common_conf.federation_enabled:
            ds_node_number = int(unique_conf.node_number) + 100 + x
            ds_broker_ips = self.get_broker_ips(unique_conf.technology, ds_node_number, unique_conf.cluster_size, common_conf.run_tag)
            federation_args += f"--downstream-broker-hosts {ds_broker_ips}"

        self._benchmark_status[status_id] = "started"
        exit_code = subprocess.call(["bash", "run-logged-aws-benchmark.sh", 
                                unique_conf.node_number, 
                                common_conf.key_pair, 
                                unique_conf.technology, 
                                unique_conf.broker_version, 
                                unique_conf.instance, 
                                unique_conf.volume, 
                                unique_conf.filesystem, 
                                common_conf.hosting, 
                                unique_conf.tenancy, 
                                common_conf.password, 
                                common_conf.postgres_url, 
                                common_conf.postgres_user, 
                                common_conf.postgres_pwd, 
                                playlist_entry.topology, 
                                common_conf.run_id, 
                                common_conf.username, 
                                common_conf.password, 
                                common_conf.run_tag, 
                                unique_conf.core_count, 
                                unique_conf.threads_per_core, 
                                unique_conf.config_tag, 
                                str(unique_conf.cluster_size), 
                                unique_conf.no_tcp_delay, 
                                policies, 
                                str(common_conf.override_step_seconds), 
                                str(common_conf.override_step_repeat), 
                                nodes, 
                                str(common_conf.override_step_msg_limit), 
                                common_conf.override_broker_hosts, 
                                unique_conf.pub_connect_to_node,
                                unique_conf.con_connect_to_node,
                                common_conf.mode,
                                str(playlist_entry.grace_period_sec),
                                str(run_ordinal),
                                common_conf.tags,
                                playlist_entry.get_topology_variables(),
                                playlist_entry.get_policy_variables(),
                                federation_args])

        if exit_code != 0:
            console_out(self.actor, f"Benchmark {unique_conf.node_number} failed")
            self._benchmark_status[status_id] = "failed"
        else:
            self._benchmark_status[status_id] = "success"

    def run_background_load(self, unique_conf, common_conf):
        console_out(self.actor, f"Starting background load for {unique_conf.node_number}")
        status_id = unique_conf.technology + unique_conf.node_number

        broker_user = "benchmark"
        broker_password = common_conf.password
        topology = common_conf.background_topology_file
        policies = common_conf.background_policies_file
        step_seconds = str(common_conf.background_step_seconds)
        step_repeat = str(common_conf.background_step_repeat)

        nodes = ""
        for x in range(int(unique_conf.cluster_size)):
            comma = ","
            if x == 0:
                comma = ""

            node_number = int(unique_conf.node_number) + x
            nodes = f"{nodes}{comma}{node_number}"

        self._benchmark_status[status_id] = "started"
        subprocess.Popen(["bash", "run-background-load-aws.sh", 
                        broker_user, 
                        broker_password, 
                        str(unique_conf.cluster_size), 
                        common_conf.key_pair, 
                        unique_conf.node_number, 
                        nodes, 
                        policies, 
                        step_seconds, 
                        step_repeat, 
                        common_conf.run_tag, 
                        unique_conf.technology, 
                        topology, 
                        unique_conf.broker_version])


    def get_broker_ips(self, technology, node, cluster_size, run_tag):
        broker_ips = ""
        attempts = 0
        while broker_ips == "" and attempts < 3:
            attempts += 1
            process = subprocess.Popen(["bash", "get_broker_ips.sh", 
                            str(cluster_size), 
                            str(node), 
                            run_tag,
                            technology], stdout=subprocess.PIPE)
            
            for line in io.TextIOWrapper(process.stdout, encoding="utf-8"):
                if not line:
                    break
                
                if line.startswith("BROKER_IPS="):
                    broker_ips = line.rstrip().replace("BROKER_IPS=","")
                    break

            if broker_ips == "":
                time.sleep(5)

        return broker_ips