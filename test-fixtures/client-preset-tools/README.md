# Owned standalone client settings controls

This historical fixture directory now exercises YAML saves and explicit `/lss reload`.
It requires Sodium and terrain renderers to be absent, with the real Xaero bridge
installed. Initial receive and map ingestion settings are both false (stage
`integrations.map_ingestion.enabled: false` explicitly: new settings files enable it). The
fixture uses the maintained owned rig and never launches an independent supervisor.

Stage the four Python tools into `settings-tools/` and freeze their hashes plus the
selected repository's shared settings helper and Java codec/schema dependencies.
Run staged tools or byte-identical portable copies:

```sh
python3 RUN/settings-tools/drive.py RUN --repo REPO --joined-layout INSPECTED_LAYOUT_JSON
python3 RUN/settings-tools/verify.py RUN --repo REPO
python3 RUN/settings-tools/finalize.py RUN --repo REPO --visuals-checked
```

The inspected layout binds the owned run and screenshot, status button coordinates,
`disconnect_to_title` and `rejoin` action lists (coordinate role names or `key:Escape`),
and the fresh `joined_log_marker` observed for this native route. Each rejoin must
produce a new matching log interval and a connected status export.

Six assertions cover standalone status, save-only behavior, hot receive reload,
physical-connection freeze and reconnect of map ingestion enable, atomic rejection of invalid
YAML, and full restoration. Captures live in `evidence/standalone-settings/`; settings
copies are YAML and typed diagnostic exports remain JSON. The verifier independently
checks settings scope, fresh command feedback, immutable source/artifact hashes,
active exported state, and both reconnect observations.

The operator must inspect the retained standalone status screenshot before supplying
`--visuals-checked`. This is separate from raw controls, owned process cleanup, and
final rig acceptance. Historical evidence remains immutable and cannot be replayed
against these new source hashes. Portability tests use synthetic files only:

```sh
python3 -m unittest discover -s test-fixtures/client-preset-tools -p 'test_*.py'
```
