# nginx static server for the Sysifos SPA. `dist` is produced by `npm run build`
# (gitignored) before `docker build` — see `just publish-fe-image sysifos`.
FROM nginx:alpine
COPY dist /usr/share/nginx/html
COPY nginx.conf.template /etc/nginx/nginx.conf.template

COPY scripts/generate-env.sh /docker-entrypoint.d/99-generate-env.sh
COPY scripts/nginx-entrypoint.sh /docker-entrypoint.d/15-nginx-envsubst.sh
RUN chmod +x /docker-entrypoint.d/99-generate-env.sh \
    && chmod +x /docker-entrypoint.d/15-nginx-envsubst.sh
EXPOSE 7602
CMD ["nginx", "-g", "daemon off;"]
