#!/bin/sh
set -eu

mkdir -p /etc/nginx/conf.d
envsubst '${AERIAL_TOKEN}' \
    < /etc/nginx/templates/default.conf.template \
    > /etc/nginx/conf.d/default.conf

exec /usr/bin/supervisord -n -c /etc/supervisord.conf
