# Stage 1: Runtime
FROM nginx:alpine
COPY dist /usr/share/nginx/html
COPY nginx.conf.template /etc/nginx/nginx.conf.template

COPY scripts/generate-env.sh /docker-entrypoint.d/99-generate-env.sh
COPY scripts/nginx-entrypoint.sh /docker-entrypoint.d/15-nginx-envsubst.sh
RUN chmod +x /docker-entrypoint.d/99-generate-env.sh \
    && chmod +x /docker-entrypoint.d/15-nginx-envsubst.sh
EXPOSE 7012
CMD ["nginx", "-g", "daemon off;"]
