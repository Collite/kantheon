# nginx static server for the Landing SPA. `dist` is produced on the HOST by `just build-fe
# landing` (npm ci → the pinned toolchain) BEFORE `docker build` — the same contract iris/sysifos
# use (publish-fe-image runs build-fe first). The image just packages the pre-built dist: no
# in-container node build, so no esbuild host/binary version skew (the earlier `npm install` inside
# the image pulled a 0.28.x binary against the pinned 0.27.3 host and aborted the vite build).
FROM nginx:alpine
COPY dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

COPY scripts/generate-env.sh /docker-entrypoint.d/99-generate-env.sh
RUN chmod +x /docker-entrypoint.d/99-generate-env.sh
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
