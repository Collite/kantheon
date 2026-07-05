{{/*
Hebe overrides the kantheon-service volume hooks (the consuming chart's define wins
over the library's empty default) to mount the per-instance Secret read-only, gated
on instanceSecrets.secretName. The Secret is provisioned by the deploying context
(provision.sh), not templated here. Byte-equivalent to the kustomize base mount.
*/}}
{{- define "kantheon-service.volumeMounts" -}}
{{- if .Values.instanceSecrets.secretName }}
          volumeMounts:
            - name: instance-secrets
              mountPath: {{ .Values.instanceSecrets.mountPath }}
              readOnly: true
{{- end }}
{{- end -}}
{{- define "kantheon-service.volumes" -}}
{{- if .Values.instanceSecrets.secretName }}
      volumes:
        - name: instance-secrets
          secret:
            secretName: {{ .Values.instanceSecrets.secretName }}
{{- end }}
{{- end -}}
