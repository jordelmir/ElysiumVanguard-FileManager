FROM node:22-alpine

WORKDIR /srv/elysium-ai-gateway
COPY package.json ./
COPY src ./src

ENV NODE_ENV=production
EXPOSE 8787
USER node
CMD ["node", "src/server.mjs"]
