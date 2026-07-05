{{/*
Shared naming/label/image helpers. Keyed off the CONSUMING chart (.Chart.Name /
.Chart.Version / .Chart.AppVersion), so a module needs no _helpers.tpl of its own —
the library reproduces the exact output the per-module helpers used to emit.
*/}}

{{/* Chart name (overridable via nameOverride). */}}
{{- define "kantheon-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Fully qualified app name. */}}
{{- define "kantheon-service.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- include "kantheon-service.name" . | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{/* Common labels. */}}
{{- define "kantheon-service.labels" -}}
app.kubernetes.io/name: {{ include "kantheon-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{/* Selector labels (stable across upgrades). */}}
{{- define "kantheon-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "kantheon-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* Resolved image reference. */}}
{{- define "kantheon-service.image" -}}
{{- $tag := .Values.image.tag | default .Chart.AppVersion -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}

{{/*
Optional pod/container extension hooks. Empty by default; a module OVERRIDES the
one it needs in its own templates/ (the consuming chart's define wins over the
library's). Used for e.g. golem's mounted Shem bundle. The library includes them
in the deployment; an empty override renders nothing (no stray whitespace).
*/}}
{{- define "kantheon-service.volumeMounts" -}}{{- end -}}
{{- define "kantheon-service.volumes" -}}{{- end -}}
