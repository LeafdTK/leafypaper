{{/* Common labels and naming for every resource in this chart. */}}

{{- define "leafypaper.namespace" -}}
{{ .Values.namespace.name }}
{{- end -}}

{{- define "leafypaper.fullname" -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "leafypaper.master.fullname" -}}
{{- printf "%s-master" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "leafypaper.server.fullname" -}}
{{- printf "%s-server" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "leafypaper.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end -}}

{{/* Build the comma-separated string of -Dmultipaper.hotspot.* JVM args. */}}
{{- define "leafypaper.master.hotspotFlags" -}}
{{- $h := .Values.master.hotspot -}}
{{- with $h.thresholdPlayers }}-Dmultipaper.hotspot.thresholdPlayers={{ . }} {{ end -}}
{{- with $h.releaseThresholdPlayers }}-Dmultipaper.hotspot.releaseThresholdPlayers={{ . }} {{ end -}}
{{- with $h.cooldownSeconds }}-Dmultipaper.hotspot.cooldownSeconds={{ . }} {{ end -}}
{{- with $h.releaseHoldSeconds }}-Dmultipaper.hotspot.releaseHoldSeconds={{ . }} {{ end -}}
{{- with $h.regionSizeChunks }}-Dmultipaper.hotspot.regionSizeChunks={{ . }} {{ end -}}
{{- with $h.dryRun }}-Dmultipaper.hotspot.dryRun={{ . }} {{ end -}}
{{- with $h.crowdServers }}-Dmultipaper.hotspot.crowdServers={{ . }} {{ end -}}
{{- end -}}

{{- define "leafypaper.server.hotspotFlags" -}}
{{- $h := .Values.server.hotspotReporter -}}
{{- with $h.regionSizeChunks }}-Dmultipaper.hotspot.regionSizeChunks={{ . }} {{ end -}}
{{- with $h.reportIntervalTicks }}-Dmultipaper.hotspot.reportIntervalTicks={{ . }} {{ end -}}
{{- end -}}
