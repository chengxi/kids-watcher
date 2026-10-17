FROM node:22-bookworm-slim AS build
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ \
    && rm -rf /var/lib/apt/lists/*
COPY package.json package-lock.json ./
COPY packages/protocol/package.json packages/protocol/
COPY services/hub/package.json services/hub/
RUN npm ci
COPY tsconfig.base.json ./
COPY packages/protocol packages/protocol
COPY services/hub services/hub
# --force rebuilds every referenced project unconditionally. Without it, a
# stale tsconfig.tsbuildinfo reaching the context makes tsc skip building
# @kids-monitor/protocol, and the hub then fails to resolve its types.
RUN npx tsc -b services/hub --force

FROM node:22-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production HUB_DATA_DIR=/data
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/packages/protocol/dist ./packages/protocol/dist
COPY --from=build /app/packages/protocol/package.json ./packages/protocol/
COPY --from=build /app/services/hub/dist ./services/hub/dist
COPY --from=build /app/services/hub/package.json ./services/hub/
VOLUME ["/data"]
EXPOSE 8443
CMD ["node", "services/hub/dist/index.js"]
