# Angular 22 (the "@angular/build:application" builder in angular.json)
# requires Node >= 22.22.3 / 24.15.0 / 26.0.0 - verified locally: the repo's
# checked-in node_modules refuse to build under Node 18. node:26-alpine here
# matches the Node 26.5.0 this was actually built and verified against.
FROM node:26-alpine AS build
WORKDIR /app

# Dependencies layer first so `npm ci` is cache-hit on source-only changes.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# defaultConfiguration in angular.json is "production" - no extra flag needed.
RUN npm run build

# Static output only - verified build path: dist/easyshop-web/browser
# (Angular's application builder splits browser/server output; there is no
# server bundle here since this app has no SSR providers in app.config.ts).
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/easyshop-web/browser /usr/share/nginx/html

EXPOSE 80
