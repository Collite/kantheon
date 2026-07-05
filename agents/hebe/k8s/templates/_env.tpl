{{/* hebe container env — profile/instance/secrets-dir + OTel + extraEnv passthrough.
     The base carries no OTEL vars; the standard OTel block is added here (env-agnostic,
     off by default) for parity with the constellation. */}}
{{- define "hebe.env" -}}
- name: HEBE_PROFILE
  value: {{ .Values.hebe.profile | quote }}
- name: HEBE_INSTANCE_ID
  value: {{ .Values.hebe.instanceId | quote }}
- name: HEBE_SECRETS_DIR
  value: {{ .Values.instanceSecrets.mountPath | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_HEBE
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
