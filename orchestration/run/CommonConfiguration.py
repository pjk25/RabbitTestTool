import uuid
from command_args import get_args, get_optional_arg, get_mandatory_arg, get_mandatory_arg_no_print, is_true, get_mandatory_arg_validated, get_optional_arg_validated

class CommonConfiguration:

    def __init__(self, args):
        self.run_id = str(uuid.uuid4())
        self.tags = get_mandatory_arg(args, "--tags", "")
        self.mode = get_optional_arg_validated(args, "--mode", "", ["benchmark","model"], "benchmark")
        self.config_count = int(get_optional_arg(args, "--config-count", "", "1"))
        self.new_instance_per_run = is_true(get_optional_arg(args, "--new-instance-per-run", "", "false"))
        self.no_destroy = is_true(get_optional_arg(args, "--no-destroy", "", "false"))
        self.no_deploy = is_true(get_optional_arg(args, "--no-deploy", "", "false"))
        self.run_tag = get_optional_arg(args, "--run-tag", "", "none")
        self.playlist_file = get_mandatory_arg(args, "--playlist-file", "")
        self.background_policies_file = get_optional_arg(args, "--bg-policies-file", "", "none")
        self.background_topology_file = get_optional_arg(args, "--bg-topology-file", "", "none")
        self.background_delay = int(get_optional_arg(args, "--bg-delay", "", "0"))
        self.background_step_seconds = int(get_optional_arg(args, "--bg-step-seconds", "", "0"))
        self.background_step_repeat = int(get_optional_arg(args, "--bg-step-repeat", "", "0"))
        self.gap_seconds = int(get_mandatory_arg(args, "--gap-seconds", ""))
        self.repeat_count = int(get_optional_arg(args, "--repeat", "", "1"))
        self.parallel_count = int(get_optional_arg(args, "--parallel", "", "1"))
        self.override_step_seconds = int(get_optional_arg(args, "--override-step-seconds", "", "0"))
        self.override_step_repeat = int(get_optional_arg(args, "--override-step-repeat", "", "0"))
        self.override_step_msg_limit = int(get_optional_arg(args, "--override-step-msg-limit", "", "0"))
        self.override_broker_hosts = get_optional_arg(args, "--override-broker-hosts", "", "")
        self.federation_enabled = is_true(get_optional_arg(args, "--federation-enabled", "", "false"))
        
        self.username = "benchmark"
        self.password = get_mandatory_arg(args, "--password", "")
        self.postgres_url = get_mandatory_arg(args, "--postgres-jdbc-url", "")
        self.postgres_user = get_mandatory_arg(args, "--postgres-user", "")
        self.postgres_pwd = get_mandatory_arg_no_print(args, "--postgres-password", "")
        self.node_counter = int(get_optional_arg(args, "--start-node-num-from", "", "1"))
        self.log_level = get_optional_arg(args, "--log-level", "", "info")