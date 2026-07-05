{{/*
Backend (Kotlin/Ktor service · agent · worker · Python service) Deployment.
The per-module container env block is delegated to `<module>.env` (defined in the
consuming chart). The gRPC port + its env are gated on `.Values.ports.grpc` so
HTTP-only modules omit them. Optional volumeMounts/volumes come from the override
hooks (kantheon-service.volumeMounts / .volumes), empty unless a module needs them.
*/}}
{{- define "kantheon-service.deployment" -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "kantheon-service.fullname" . }}
  labels:
    {{- include "kantheon-service.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      {{- include "kantheon-service.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      {{- with .Values.podAnnotations }}
      annotations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      labels:
        {{- include "kantheon-service.selectorLabels" . | nindent 8 }}
    spec:
      {{- with .Values.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      containers:
        - name: {{ .Chart.Name }}
          image: {{ include "kantheon-service.image" . | quote }}
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: {{ .Values.ports.http }}
            {{- if .Values.ports.grpc }}
            - name: grpc
              containerPort: {{ .Values.ports.grpc }}
            {{- end }}
          env:
            {{- include (printf "%s.env" .Chart.Name) . | nindent 12 }}
          {{- with .Values.envFrom }}
          envFrom:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          readinessProbe:
            httpGet:
              path: {{ .Values.readinessProbe.path }}
              port: {{ .Values.ports.http }}
            initialDelaySeconds: {{ .Values.readinessProbe.initialDelaySeconds }}
            periodSeconds: {{ .Values.readinessProbe.periodSeconds }}
            failureThreshold: {{ .Values.readinessProbe.failureThreshold }}
          livenessProbe:
            httpGet:
              path: {{ .Values.livenessProbe.path }}
              port: {{ .Values.ports.http }}
            initialDelaySeconds: {{ .Values.livenessProbe.initialDelaySeconds }}
            periodSeconds: {{ .Values.livenessProbe.periodSeconds }}
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          {{- include "kantheon-service.volumeMounts" . }}
      {{- include "kantheon-service.volumes" . }}
      {{- with .Values.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.affinity }}
      affinity:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.tolerations }}
      tolerations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
{{- end -}}
