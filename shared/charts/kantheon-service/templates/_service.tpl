{{/*
Backend ClusterIP Service. `http` always; `grpc` gated on `.Values.ports.grpc`
(HTTP-only modules leave it unset). port == targetPort == the container port.
*/}}
{{- define "kantheon-service.service" -}}
apiVersion: v1
kind: Service
metadata:
  name: {{ include "kantheon-service.fullname" . }}
  labels:
    {{- include "kantheon-service.labels" . | nindent 4 }}
spec:
  type: {{ .Values.service.type }}
  selector:
    {{- include "kantheon-service.selectorLabels" . | nindent 4 }}
  ports:
    - name: http
      port: {{ .Values.ports.http }}
      targetPort: {{ .Values.ports.http }}
    {{- if .Values.ports.grpc }}
    - name: grpc
      port: {{ .Values.ports.grpc }}
      targetPort: {{ .Values.ports.grpc }}
    {{- end }}
{{- end -}}
