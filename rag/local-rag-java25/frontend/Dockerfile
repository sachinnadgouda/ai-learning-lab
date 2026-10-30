FROM node:22-alpine AS build
WORKDIR /ui
COPY package*.json ./
RUN npm install --no-audit --no-fund --fetch-retries=3
COPY angular.json tsconfig*.json ./
COPY src ./src
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /ui/dist/local-rag-ui/browser/ /usr/share/nginx/html/
EXPOSE 80
