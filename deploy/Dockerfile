# Quick Voice signaling server — single-container build.
FROM node:20-alpine

WORKDIR /app

COPY server/package.json server/package-lock.json ./
RUN npm install --omit=dev

COPY server/server.js .
COPY server/smoke-test.js .
COPY server/tunnel-test.js .

# APK releases are published by dropping files here.
COPY server/releases /app/releases
RUN mkdir -p /app/releases

ENV PORT=8080
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://127.0.0.1:8080/api/version || exit 1

CMD ["node", "server.js"]
