# Requirements for the Landing Page

This is a landing page that a) displays a basic dashboard about the cluster and b) serves as a dispatcher for the other frontends on the cluster

## Design

There is a title and a logo in the header section. The title is `Kantheon` and the logo is just a placeholder for now.

The page is horizontally divided into two halves.

The top half contains a cluster dashboard. This will contain a grafana-served dashboard from the cluster's Grafana

Bottom half contains (static) links to different applications.
It is split vertically into two halves again. The left half's content is:
```
- Agent
- Services
- Developer's Portal
- Grafana
```

The right half's content is
```
- ArgoCD
- Traefik
- Keycloak
```

## Requirements

This is a single page application in TS and Vue.
The URL of the dashboard and the links are specified using environmental variables


