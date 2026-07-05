{{/* iris-bff container env — lifted verbatim from the pre-library chart (D1). */}}
{{- define "iris-bff.env" -}}
- name: IRIS_BFF_HTTP_PORT
  value: {{ .Values.ports.http | quote }}
- name: IRIS_DB_ENABLED
  value: {{ .Values.db.enabled | quote }}
{{- if .Values.db.enabled }}
- name: IRIS_DB_HOST
  value: {{ .Values.db.host | quote }}
- name: IRIS_DB_PORT
  value: {{ .Values.db.port | quote }}
- name: IRIS_DB_NAME
  value: {{ .Values.db.name | quote }}
- name: IRIS_DB_USER
  value: {{ .Values.db.user | quote }}
- name: IRIS_DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ required "db.existingSecret is required when db.enabled" .Values.db.existingSecret }}
      key: {{ .Values.db.passwordKey }}
{{- end }}
- name: IRIS_GOLEM_V2_BASE_URL
  value: {{ .Values.golemV2BaseUrl | quote }}
- name: IRIS_AUTH_VERIFY_SIGNATURE
  value: {{ .Values.auth.verifySignature | quote }}
{{- with .Values.auth.keycloakIssuer }}
- name: IRIS_AUTH_KEYCLOAK_ISSUER
  value: {{ . | quote }}
{{- end }}
{{- with .Values.auth.jwksUri }}
- name: IRIS_AUTH_JWKS_URI
  value: {{ . | quote }}
{{- end }}
{{- with .Values.auth.audience }}
- name: IRIS_AUTH_AUDIENCE
  value: {{ . | quote }}
{{- end }}
{{- with .Values.audit.signingKeyRef }}
- name: IRIS_AUDIT_SIGNING_KEY_REF
  value: {{ . | quote }}
{{- end }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_IRIS_BFF
  value: {{ .Values.telemetry.enabled | quote }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
