{{/*
midas-excel-loader overrides the kantheon-service volume hooks (the consuming chart's
define wins over the library's empty default) to mount an emptyDir scratch volume for
uploaded statement blobs, gated on blobs.enabled. Byte-equivalent to the kustomize base.
*/}}
{{- define "kantheon-service.volumeMounts" -}}
{{- if .Values.blobs.enabled }}
          volumeMounts:
            - name: blobs
              mountPath: {{ .Values.blobs.mountPath }}
{{- end }}
{{- end -}}
{{- define "kantheon-service.volumes" -}}
{{- if .Values.blobs.enabled }}
      volumes:
        - name: blobs
          emptyDir: {}
{{- end }}
{{- end -}}
