# Ansible Role: Domino IQ

Enables HCL Domino IQ on a provisioned Domino 14.5 or newer server. The role installs the
Domino IQ Llama Server add-on into the Domino program directory, lists the server in the
Directory Profile, restarts Domino so it creates `dominoiq.nsf` and `llm_models`, and writes
the `dominoiq.nsf` Configuration document through Genesis.

## Requirements

- Domino 14.5 or newer, installed by `startcloud.hcl_roles.domino_install`.
- Genesis, installed by `startcloud.hcl_roles.domino_genesis`.
- Domino IQ RAG in Remote mode needs Domino 14.5.1 with FP1 installed
  (`domino_installer_fixpack_install: true`, `domino_fixpack_version: FP1`).
- Local mode needs an NVIDIA GPU on the server.

## Role Variables

Available variables are listed below, along with default values (see `defaults/main.yml`):

    run_tasks: true

Master gate — when false the role loads its variables but runs no tasks.

    domino_iq_mode: remote

`remote` sends completions to an OpenAI compatible HTTPS endpoint; `local` runs the GGUF model
on the server.

    domino_iq_endpoint_url: "https://webui.assistant.prominic.net/api/chat/completions"
    domino_iq_model: "llama3.1:latest"
    domino_iq_api_key: "{{ (secrets | default({})).get('domino_iq_api_key', '') }}"

Remote endpoint, model name and API key. The API key belongs in `.secrets.yml` as
`domino_iq_api_key`. The role fails before any change when one of them is empty.

    domino_iq_server_name: "CN={{ domino_server_name_common }}/O={{ domino_organization }}{{ domino_country_suffix | default('') }}"
    domino_iq_servers:
      - "{{ domino_iq_server_name }}"
    domino_iq_admin_server: "{{ domino_iq_server_name }}"
    domino_iq_directory_profile_update: "{{ not (is_additional_server | default(false)) }}"

Directory Profile values. Servers are appended to `DomIQServers`; `DomIQAdminServer` is set only
when empty. Additional servers skip the Directory Profile and must already be listed by the
Domino IQ administration server.

    domino_iq_use_max_completion_tokens: false

Adds `DOMIQ_USE_MAX_COMPLETION_TOKENS=1` to `notes.ini` for GPT-5.1 style remote models.

    domino_iq_trusted_root_files: []

PEM files on the guest imported into `certstore.nsf` as trusted roots when the remote endpoint
certificate does not chain to a root `certstore.nsf` already trusts.

    domino_iq_llama_server_app: Domino-IQ
    domino_iq_llama_server_version: "2025.08"
    domino_iq_llama_server_variant: release
    domino_iq_llama_server_archive: LlamaServerforDominoIQ_061726_Linux.zip

BoxVault coordinates of the Domino IQ Llama Server archive, downloaded through `installer_url`.

    domino_iq_local_use_tls: false
    domino_iq_local_port: "{{ 8443 if domino_iq_local_use_tls else 8080 }}"
    domino_iq_local_model_app: Domino-IQ
    domino_iq_local_model_version: "2025.08"
    domino_iq_local_model_variant: release
    domino_iq_local_model_archive: ""
    domino_iq_local_model_description: "{{ domino_iq_model }}"

Local mode only: port of the local AI engine and the BoxVault coordinates of the GGUF model
copied into `llm_models`.

## Dependencies

None.

## Example Playbook

    - hosts: all
      become: true
      roles:
        - name: startcloud.hcl_roles.domino_iq
          vars:
            run_tasks: true

## License

GPL-2.0-or-later
