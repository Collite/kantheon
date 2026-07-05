{{/* golem container env — lifted verbatim from the pre-library chart (D1). */}}
{{- define "golem.env" -}}
- name: GOLEM_HTTP_PORT
  value: {{ .Values.ports.http | quote }}
- name: GOLEM_DB_ENABLED
  value: {{ .Values.db.enabled | quote }}
{{- if .Values.db.enabled }}
- name: GOLEM_DB_HOST
  value: {{ .Values.db.host | quote }}
- name: GOLEM_DB_PORT
  value: {{ .Values.db.port | quote }}
- name: GOLEM_DB_NAME
  value: {{ .Values.db.name | quote }}
- name: GOLEM_DB_USER
  value: {{ .Values.db.user | quote }}
- name: GOLEM_DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ required "db.existingSecret is required when db.enabled" .Values.db.existingSecret }}
      key: {{ .Values.db.passwordKey }}
{{- end }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_GOLEM
  value: {{ .Values.telemetry.enabled | quote }}
{{- if .Values.shem.configMapName }}
- name: GOLEM_SHEM_DIR
  value: {{ .Values.shem.mountPath | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
