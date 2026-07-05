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
