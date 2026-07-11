{{/*
Golem overrides the kantheon-service volume hooks (the consuming chart's define
wins over the library's empty default) to mount the assembled Shem bundle, gated
on shem.configMapName. Byte-equivalent to the pre-library golem deployment.
*/}}
{{- define "kantheon-service.volumeMounts" -}}
{{- if .Values.shem.configMapName }}
          volumeMounts:
            # The assembled Shem bundle (overlay shem.yaml + prompts/{locale}/…),
            # mounted read-only at GOLEM_SHEM_DIR. The ConfigMap is created from
            # agents/golem/shems/<agent_id>/ by the deploying context (olymp /
            # `kubectl create configmap --from-file`), not templated here.
            - name: shem
              mountPath: {{ .Values.shem.mountPath }}
              readOnly: true
{{- end }}
{{- end -}}
{{- define "kantheon-service.volumes" -}}
{{- if .Values.shem.configMapName }}
      volumes:
        - name: shem
          configMap:
            name: {{ .Values.shem.configMapName }}
{{- end }}
{{- end -}}
{{/*
A Shem-loaded Golem resolves its area→packages from Veles at boot ONE-SHOT (no auto-retry;
a boot-time failure leaves the pod permanently not-ready). So when a Shem is mounted, block
startup on an initContainer until Veles's model is loaded — Veles's /ready returns 503 until
`registry.read()` is non-null, so this waits out the deploy race (Golem + Veles start together).
Skeleton Golem (no shem.configMapName) needs no model and renders no initContainer.
*/}}
{{- define "kantheon-service.initContainers" -}}
{{- if .Values.shem.configMapName }}
      initContainers:
        - name: wait-for-veles
          image: {{ .Values.velesGate.image }}
          command:
            - sh
            - -c
            - "until wget -q -O- {{ .Values.velesGate.url }} >/dev/null 2>&1; do echo 'waiting for veles /ready'; sleep 3; done; echo 'veles is model-ready'"
{{- end }}
{{- end -}}
