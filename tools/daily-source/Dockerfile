FROM nginx:alpine

# nginx serves the API and images; python runs the daily generator. One container,
# two supervised processes, so the TV keeps the same single host/port.
RUN apk add --no-cache \
    python3 \
    py3-pillow \
    tzdata \
    supervisor \
    gettext

WORKDIR /app
COPY gen/gen.py /app/gen.py
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY deploy/entrypoint.sh /entrypoint.sh
COPY deploy/supervisord.conf /etc/supervisord.conf
RUN chmod +x /entrypoint.sh

ENV TZ=Asia/Shanghai \
    GEN_HOUR=4 \
    DAYS=8 \
    UHD=1 \
    DATA_DIR=/data \
    STATE_DIR=/state \
    PHOTOS_DIR=/photos \
    ALBUM_TITLE="Local album" \
    ALBUM_COPYRIGHT="From local album" \
    AERIAL_TOKEN=

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s \
    CMD wget -q -O /dev/null --header="X-Aerial-Token: ${AERIAL_TOKEN}" http://127.0.0.1/health || exit 1

CMD ["/entrypoint.sh"]
