{{/*
Hebe overrides the kantheon-service volume hooks (the consuming chart's define wins over
the library's empty default) to mount TWO things read-only:
  1. the axis config.toml ConfigMap at $HOME/.hebe/config.toml (always, when config.enabled)
     — without it the k8s profile can't resolve instance_id (see configmap.yaml);
  2. the per-instance Secret at instanceSecrets.mountPath (gated on secretName) — PG creds,
     Keycloak client, llm-gateway key, receipts signing key; provisioned by provision.sh.
The `volumeMounts:` / `volumes:` keys render whenever EITHER is present (avoids an empty key).
*/}}
{{- define "kantheon-service.volumeMounts" -}}
{{- if or .Values.config.enabled .Values.instanceSecrets.secretName }}
          volumeMounts:
{{- if .Values.config.enabled }}
            - name: hebe-config
              mountPath: {{ printf "%s/.hebe/config.toml" .Values.config.homeDir | quote }}
              subPath: config.toml
              readOnly: true
{{- end }}
{{- if .Values.instanceSecrets.secretName }}
            - name: instance-secrets
              mountPath: {{ .Values.instanceSecrets.mountPath }}
              readOnly: true
{{- end }}
{{- end }}
{{- end -}}
{{- define "kantheon-service.volumes" -}}
{{- if or .Values.config.enabled .Values.instanceSecrets.secretName }}
      volumes:
{{- if .Values.config.enabled }}
        - name: hebe-config
          configMap:
            name: {{ include "kantheon-service.fullname" . }}-config
{{- end }}
{{- if .Values.instanceSecrets.secretName }}
        - name: instance-secrets
          secret:
            secretName: {{ .Values.instanceSecrets.secretName }}
{{- end }}
{{- end }}
{{- end -}}
