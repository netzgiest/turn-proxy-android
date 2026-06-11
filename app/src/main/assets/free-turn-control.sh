#!/bin/bash
# free-turn-proxy server control script.
# Streamed via SSH stdin from Android (bash -s -- <subcmd> <args>).
# Output protocol: KEY=VALUE lines, "LOG: ..." for free text, final RESULT=ok|err.
#
# Runtime modes:
#   systemd  — unit /etc/systemd/system/free-turn-proxy.service + launch.sh + run.args
#   nohup    — legacy; nohup + PIDFILE (для машин без systemd)
# Маркер режима — $PREFIX/runtime (одна строка: systemd|nohup).

set -eu

PREFIX="/opt/free-turn-proxy"
PIDFILE="$PREFIX/proxy.pid"
OBFFILE="$PREFIX/obf.key"
LOGFILE="$PREFIX/server.log"
VERFILE="$PREFIX/version"
RUNTIMEFILE="$PREFIX/runtime"
ARGSFILE="$PREFIX/run.args"
ENVFILE="$PREFIX/run.env"
LAUNCHER="$PREFIX/launch.sh"
UNIT_PATH="/etc/systemd/system/free-turn-proxy.service"
UNIT_NAME="free-turn-proxy.service"
BASE_URL="https://github.com/samosvalishe/free-turn-proxy/releases/latest/download"

# WireGuard bootstrap (wg-setup): сеть /24, .1 — сервер, .2 — первый пир.
WG_DIR="/etc/wireguard"
WG_IFACE="wg0"
WG_CONF="$WG_DIR/$WG_IFACE.conf"
WG_NET="10.13.13"
WG_CLIENT_CONF="$PREFIX/wireguard-client.conf"

log()  { echo "LOG: $*"; }
emit() { echo "$1=$2"; }
die()  { echo "ERR=$*"; echo "RESULT=err"; trap - EXIT; exit 1; }

trap 'rc=$?; if [ $rc -ne 0 ]; then echo "ERR=script exit $rc"; echo "RESULT=err"; fi' EXIT

_mips_is_le() {
    # 1=LE, 0=BE. od на little-endian машине '\1\0' читает как 0x0001.
    local hex
    hex=$(printf '\1\0' | od -An -tx2 -N2 2>/dev/null | tr -d ' \n')
    [ "$hex" = "0001" ]
}

detect_arch() {
    local m
    m=$(uname -m)
    case "$m" in
        x86_64|amd64) echo "server-linux-amd64" ;;
        aarch64|arm64) echo "server-linux-arm64" ;;
        armv7l|armv6l|armv5*|arm) echo "server-linux-arm" ;;
        i386|i486|i586|i686) echo "server-linux-386" ;;
        riscv64) echo "server-linux-riscv64" ;;
        mips64|mips64le)
            if _mips_is_le; then echo "server-linux-mips64le"
            else die "unsupported arch: mips64 BE (no asset)"
            fi
            ;;
        mips|mipsel|mipsle)
            if _mips_is_le; then echo "server-linux-mipsle"
            else echo "server-linux-mips"
            fi
            ;;
        *) die "unsupported arch: $m" ;;
    esac
}

binpath() { echo "$PREFIX/$(detect_arch)"; }

# Доступен ли systemd на этой машине.
has_systemd() {
    command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]
}

current_runtime() {
    if [ -f "$RUNTIMEFILE" ]; then
        cat "$RUNTIMEFILE"
    elif has_systemd; then
        echo "systemd"
    else
        echo "nohup"
    fi
}

# --- состояние ---

is_running_nohup() {
    if [ -f "$PIDFILE" ]; then
        local pid
        pid=$(cat "$PIDFILE" 2>/dev/null || echo "")
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            return 0
        fi
        rm -f "$PIDFILE"
    fi
    if pgrep -f "^$PREFIX/server-linux-" >/dev/null 2>&1; then
        return 0
    fi
    return 1
}

is_running_systemd() {
    systemctl is-active --quiet "$UNIT_NAME"
}

# echo текущий PID процесса (или пусто).
current_pid() {
    case "$(current_runtime)" in
        systemd)
            local p
            p=$(systemctl show -p MainPID --value "$UNIT_NAME" 2>/dev/null || echo 0)
            [ "$p" != "0" ] && [ -n "$p" ] && echo "$p"
            ;;
        nohup)
            [ -f "$PIDFILE" ] && cat "$PIDFILE" 2>/dev/null
            ;;
    esac
}

# Возвращает cmdline текущего процесса (или пусто).
current_cmdline() {
    local p
    p=$(current_pid)
    if [ -n "$p" ] && [ -r "/proc/$p/cmdline" ]; then
        tr '\0' ' ' < "/proc/$p/cmdline"
    fi
}

cmd_probe() {
    local bin
    bin=$(binpath)
    if [ -x "$bin" ]; then
        emit INSTALLED yes
        local sha
        sha=$(sha256sum "$bin" 2>/dev/null | awk '{print $1}')
        [ -n "$sha" ] && emit BIN_SHA256 "$sha"
        if [ -f "$VERFILE" ]; then
            emit VERSION "$(cat "$VERFILE")"
        fi
    else
        emit INSTALLED no
    fi
    emit RUNTIME "$(current_runtime)"

    local running=no
    case "$(current_runtime)" in
        systemd) is_running_systemd && running=yes ;;
        nohup)   is_running_nohup   && running=yes ;;
    esac

    if [ "$running" = "yes" ]; then
        emit RUNNING yes
        local cmdline
        cmdline=$(current_cmdline)
        # -mode tcp → MODE=tcp, иначе udp-релей (дефолт ядра).
        if echo "$cmdline" | grep -q -- "-mode tcp"; then emit MODE tcp; else emit MODE udp; fi
        # OBF=<профиль из cmdline> (rtpopus|none).
        local obf
        obf=$(echo "$cmdline" | sed -nE 's/.*-obf-profile[= ]+([a-z]+).*/\1/p')
        emit OBF "${obf:-none}"
    else
        emit RUNNING no
    fi

    # WireGuard для мастера установки: WG_PORT — порт активного интерфейса либо
    # ListenPort из conf (интерфейс не поднят). Нет WG_PORT = бэкенда-WG нет.
    if command -v wg >/dev/null 2>&1; then
        emit WG_INSTALLED yes
        local wgp=""
        wgp=$(wg show all listen-port 2>/dev/null | awk 'NF{print $NF; exit}' || true)
        if [ -z "$wgp" ] && ls "$WG_DIR"/*.conf >/dev/null 2>&1; then
            wgp=$(grep -ih '^[[:space:]]*ListenPort' "$WG_DIR"/*.conf 2>/dev/null \
                | head -n1 | sed 's/.*=[[:space:]]*//; s/[#;].*//' | tr -d ' \r' || true)
        fi
        case "$wgp" in
            ''|0|*[!0-9]*) ;;
            *) emit WG_PORT "$wgp" ;;
        esac
    else
        emit WG_INSTALLED no
    fi
    echo "RESULT=ok"
    trap - EXIT
}

_dl() {
    local url=$1 out=$2
    if command -v curl >/dev/null 2>&1; then
        curl -sSL --fail -o "$out" "$url"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$out" "$url"
    else
        die "neither curl nor wget present"
    fi
}

_resolve_version() {
    # GitHub `latest/download/<asset>` отвечает 302 с Location, содержащим тег
    # вида .../releases/download/vX.Y.Z/<asset>. Берём из URL.
    local url="$1" loc=""
    if command -v curl >/dev/null 2>&1; then
        loc=$(curl -sI "$url" 2>/dev/null | awk -F': ' 'tolower($1)=="location"{print $2}' | tr -d '\r' | head -n1)
    elif command -v wget >/dev/null 2>&1; then
        loc=$(wget --spider --server-response "$url" 2>&1 | awk '/[Ll]ocation:/{print $2}' | tr -d '\r' | head -n1)
    fi
    echo "$loc" | sed -nE 's#.*/releases/download/([^/]+)/.*#\1#p'
}

# Записать systemd unit и launcher. Идемпотентно — сравниваем содержимое,
# чтобы не дёргать daemon-reload зря.
_install_systemd_unit() {
    local need_reload=0

    local launcher_content
    launcher_content=$(cat <<'LAUNCH_EOF'
#!/bin/bash
# Авто-сгенерирован free-turn-control.sh — не редактировать вручную.
set -e
PREFIX=/opt/free-turn-proxy
mips_is_le() {
    local hex
    hex=$(printf '\1\0' | od -An -tx2 -N2 2>/dev/null | tr -d ' \n')
    [ "$hex" = "0001" ]
}
m=$(uname -m)
case "$m" in
    x86_64|amd64) arch=server-linux-amd64 ;;
    aarch64|arm64) arch=server-linux-arm64 ;;
    armv7l|armv6l|armv5*|arm) arch=server-linux-arm ;;
    i386|i486|i586|i686) arch=server-linux-386 ;;
    riscv64) arch=server-linux-riscv64 ;;
    mips64|mips64le)
        if mips_is_le; then arch=server-linux-mips64le
        else echo "unsupported arch: mips64 BE" >&2; exit 1
        fi
        ;;
    mips|mipsel|mipsle)
        if mips_is_le; then arch=server-linux-mipsle
        else arch=server-linux-mips
        fi
        ;;
    *) echo "unsupported arch: $m" >&2; exit 1 ;;
esac
[ -f "$PREFIX/run.args" ] || { echo "run.args missing" >&2; exit 1; }
mapfile -t a < "$PREFIX/run.args"
exec "$PREFIX/$arch" "${a[@]}"
LAUNCH_EOF
)

    if [ ! -f "$LAUNCHER" ] || [ "$(cat "$LAUNCHER" 2>/dev/null)" != "$launcher_content" ]; then
        printf '%s\n' "$launcher_content" > "$LAUNCHER"
        chmod 755 "$LAUNCHER"
    fi

    local unit_content
    unit_content=$(cat <<UNIT_EOF
[Unit]
Description=free-turn-proxy server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=-$ENVFILE
ExecStart=$LAUNCHER
Restart=on-failure
RestartSec=2
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
UNIT_EOF
)

    if [ ! -f "$UNIT_PATH" ] || [ "$(cat "$UNIT_PATH" 2>/dev/null)" != "$unit_content" ]; then
        printf '%s\n' "$unit_content" > "$UNIT_PATH"
        chmod 644 "$UNIT_PATH"
        need_reload=1
    fi

    if [ "$need_reload" = "1" ]; then
        systemctl daemon-reload
    fi
    systemctl enable "$UNIT_NAME" >/dev/null 2>&1 || true
}

# Завершить процесс старого nohup-режима, если он остался от прошлой версии.
_kill_legacy_nohup() {
    if [ -f "$PIDFILE" ]; then
        local pid
        pid=$(cat "$PIDFILE" 2>/dev/null || echo "")
        if [ -n "$pid" ]; then
            kill "$pid" 2>/dev/null || true
            sleep 1
            kill -9 "$pid" 2>/dev/null || true
        fi
        rm -f "$PIDFILE"
    fi
    pkill -9 -f "^$PREFIX/server-linux-" 2>/dev/null || true
}

cmd_install() {
    mkdir -p "$PREFIX" || die "cannot create $PREFIX"
    [ -w "$PREFIX" ] || die "$PREFIX not writable"
    local bin name asset_url tmp ver curver
    name=$(detect_arch)
    bin="$PREFIX/$name"
    tmp="$bin.new"
    asset_url="$BASE_URL/$name"

    log "resolving latest version"
    ver=$(_resolve_version "$asset_url")
    if [ -z "$ver" ]; then
        die "cannot resolve latest version (no Location header from $BASE_URL)"
    fi
    curver=""
    [ -f "$VERFILE" ] && curver=$(cat "$VERFILE")

    local cached=0
    if [ -x "$bin" ] && [ "$ver" = "$curver" ]; then
        cached=1
    fi

    if [ "$cached" = "0" ]; then
        log "downloading $name @ $ver"
        if ! _dl "$asset_url" "$tmp"; then
            rm -f "$tmp"
            die "binary download failed"
        fi
        # sanity-check: GitHub отдаёт HTML-404 при отсутствии ассета.
        local size
        size=$(wc -c < "$tmp" 2>/dev/null || echo 0)
        if [ "$size" -lt 100000 ]; then
            rm -f "$tmp"
            die "downloaded file too small ($size bytes)"
        fi
        chmod +x "$tmp"
        if [ -f "$bin" ]; then
            cp -f "$bin" "$bin.bak" 2>/dev/null || true
        fi
        mv -f "$tmp" "$bin"
        echo "$ver" > "$VERFILE"
    fi

    # Решаем режим запуска и подготавливаем инфраструктуру.
    local was_running=0
    case "$(current_runtime)" in
        systemd) is_running_systemd && was_running=1 ;;
        nohup)   is_running_nohup   && was_running=1 ;;
    esac

    if has_systemd; then
        # Любой остаточный nohup-процесс убиваем перед enable юнита, иначе он
        # держит порт и systemctl start не поднимется. Триггеримся на PIDFILE
        # ИЛИ висящий бинарь — RUNTIMEFILE на свежей машине ещё не показателен.
        if [ -f "$PIDFILE" ] || pgrep -f "^$PREFIX/server-linux-" >/dev/null 2>&1; then
            log "killing legacy nohup process before systemd takeover"
            _kill_legacy_nohup
        fi
        _install_systemd_unit
        echo "systemd" > "$RUNTIMEFILE"
        emit RUNTIME systemd
    else
        echo "nohup" > "$RUNTIMEFILE"
        emit RUNTIME nohup
    fi

    if [ "$cached" = "1" ]; then
        emit STAGE cached
    else
        emit STAGE downloaded
    fi
    emit BIN "$name"
    emit VERSION "$ver"
    if [ "$was_running" = "1" ] && [ "$cached" = "0" ]; then
        emit NEEDS_RESTART yes
    fi
    echo "RESULT=ok"
    trap - EXIT
}

# parse --key=value args into globals
ARG_LISTEN=""
ARG_CONNECT=""
ARG_MODE=""
ARG_OBF_PROFILE="none"
ARG_OBF_KEY=""
ARG_TAIL=80
ARG_WG_PORT=""
ARG_WG_ENDPOINT=""
ARG_WG_ADOPT=""

parse_args() {
    while [ $# -gt 0 ]; do
        case "$1" in
            --listen=*)
                ARG_LISTEN="${1#*=}"
                [[ "$ARG_LISTEN" =~ ^[a-zA-Z0-9.:_-]+$ ]] || die "bad --listen"
                ;;
            --connect=*)
                ARG_CONNECT="${1#*=}"
                [[ "$ARG_CONNECT" =~ ^[a-zA-Z0-9.:_-]+$ ]] || die "bad --connect"
                ;;
            --mode=*)
                ARG_MODE="${1#*=}"
                [[ "$ARG_MODE" =~ ^(udp|tcp)$ ]] || die "bad --mode (need udp|tcp)"
                ;;
            --obf-profile=*)
                ARG_OBF_PROFILE="${1#*=}"
                [[ "$ARG_OBF_PROFILE" =~ ^(none|rtpopus)$ ]] || die "bad --obf-profile (need none|rtpopus)"
                ;;
            --obf-key=*)
                ARG_OBF_KEY="${1#*=}"
                [[ "$ARG_OBF_KEY" =~ ^[0-9a-fA-F]{64}$ ]] || die "bad --obf-key (need 64 hex)"
                ;;
            --tail=*)
                ARG_TAIL="${1#*=}"
                [[ "$ARG_TAIL" =~ ^[0-9]+$ ]] || die "bad --tail"
                ;;
            --port=*)
                ARG_WG_PORT="${1#*=}"
                [[ "$ARG_WG_PORT" =~ ^[0-9]+$ ]] || die "bad --port"
                ;;
            --endpoint=*)
                ARG_WG_ENDPOINT="${1#*=}"
                [[ "$ARG_WG_ENDPOINT" =~ ^[a-zA-Z0-9._-]+:[0-9]{1,5}$ ]] || die "bad --endpoint (host:port)"
                ;;
            --adopt=*)
                ARG_WG_ADOPT="${1#*=}"
                [[ "$ARG_WG_ADOPT" =~ ^[01]$ ]] || die "bad --adopt (need 0|1)"
                ;;
            *) die "unknown arg: $1" ;;
        esac
        shift
    done
}

# Записать аргументы в run.args (по одному на строку, mode 600).
# Bond на сервере не задаётся — ядро детектит его по magic-префиксу стрима.
_write_args_file() {
    local tmp="$ARGSFILE.tmp"
    : > "$tmp"
    chmod 600 "$tmp"
    {
        echo "-listen"
        echo "$ARG_LISTEN"
        echo "-connect"
        echo "$ARG_CONNECT"
        if [ "$ARG_MODE" = "tcp" ]; then
            echo "-mode"
            echo "tcp"
        fi
        if [ "$ARG_OBF_PROFILE" != "none" ] && [ -n "$ARG_OBF_KEY" ]; then
            echo "-obf-profile"
            echo "$ARG_OBF_PROFILE"
            echo "-obf-key"
            echo "$ARG_OBF_KEY"
        fi
    } >> "$tmp"
    mv -f "$tmp" "$ARGSFILE"
    chmod 600 "$ARGSFILE"
}

# Записать env-переменные в run.env (KEY=VALUE, mode 600). Используется
# и systemd (EnvironmentFile), и nohup (source перед exec). Сейчас env пуст
# (KCP FEC выпилен из ядра), файл оставлен для совместимости unit-а.
_write_env_file() {
    local tmp="$ENVFILE.tmp"
    : > "$tmp"
    chmod 600 "$tmp"
    mv -f "$tmp" "$ENVFILE"
    chmod 600 "$ENVFILE"
}

cmd_start_systemd() {
    local bin
    bin=$(binpath)
    [ -x "$bin" ] || die "server binary not installed; run install first"

    _write_args_file
    _write_env_file

    if ! systemctl restart "$UNIT_NAME"; then
        die "systemctl restart failed"
    fi
    sleep 1
    if ! is_running_systemd; then
        if [ -f "$bin.bak" ]; then
            log "process not active; rolling back .bak"
            mv -f "$bin.bak" "$bin"
            systemctl restart "$UNIT_NAME" || true
            sleep 1
            is_running_systemd || die "server failed to start (after rollback); see journalctl -u $UNIT_NAME"
        else
            die "server failed to start; see journalctl -u $UNIT_NAME"
        fi
    fi
    rm -f "$bin.bak"
    local pid
    pid=$(current_pid)
    [ -n "$pid" ] && emit PID "$pid"
    echo "RESULT=ok"
    trap - EXIT
}

cmd_start_nohup() {
    local bin
    bin=$(binpath)
    [ -x "$bin" ] || die "server binary not installed; run install first"

    if is_running_nohup; then
        log "already running, stopping first"
        _stop_nohup
    fi

    cd "$PREFIX"
    _write_env_file
    if [ -f "$ENVFILE" ]; then
        set -a
        # shellcheck disable=SC1090
        . "$ENVFILE"
        set +a
    fi
    local args=(-listen "$ARG_LISTEN" -connect "$ARG_CONNECT")
    [ "$ARG_MODE" = "tcp" ] && args+=(-mode tcp)
    if [ "$ARG_OBF_PROFILE" != "none" ] && [ -n "$ARG_OBF_KEY" ]; then
        umask 077
        printf '%s' "$ARG_OBF_KEY" > "$OBFFILE"
        chmod 600 "$OBFFILE"
        args+=(-obf-profile "$ARG_OBF_PROFILE" -obf-key "$(cat "$OBFFILE")")
    fi

    nohup "$bin" "${args[@]}" >"$LOGFILE" 2>&1 &
    local pid=$!
    echo "$pid" > "$PIDFILE"
    sleep 1
    if ! kill -0 "$pid" 2>/dev/null; then
        if [ -f "$bin.bak" ]; then
            log "process died; rolling back .bak"
            mv -f "$bin.bak" "$bin"
        fi
        rm -f "$PIDFILE"
        die "server failed to start; see logs"
    fi
    rm -f "$bin.bak"
    emit PID "$pid"
    echo "RESULT=ok"
    trap - EXIT
}

# ── WireGuard bootstrap (мастер установки) ──────────────────────────────────
# Идемпотентно поднимает WG-сервер как бэкенд free-turn-proxy: ставит
# wireguard-tools, генерит ключи, пишет wg0.conf (ListenPort=--port) и клиентский
# конфиг (Endpoint=--endpoint), включает wg-quick@wg0. Существующий wg0.conf не
# перетирается (ключи стабильны). Порт WG в firewall не открывается — трафик
# приходит только через free-turn-proxy.

_wg_pkg_install() {
    if command -v apt-get >/dev/null 2>&1; then
        apt-get update -qq >/dev/null 2>&1 || true
        apt-get install -y -qq wireguard-tools >/dev/null 2>&1 \
            || apt-get install -y -qq wireguard >/dev/null 2>&1 || true
    elif command -v dnf >/dev/null 2>&1; then
        dnf install -y -q wireguard-tools >/dev/null 2>&1 || true
    elif command -v yum >/dev/null 2>&1; then
        yum install -y -q wireguard-tools >/dev/null 2>&1 || true
    fi
}

_wg_existing_port() {  # CONF_PATH
    # s/[#;].*// — срезает инлайн-комментарий после значения.
    grep -i '^[[:space:]]*ListenPort' "$1" 2>/dev/null \
        | head -n1 | sed 's/.*=[[:space:]]*//; s/[#;].*//' | tr -d ' \r' || true
}

_wg_runtime_port() {  # IFACE — фактический порт живого интерфейса (пусто, если не поднят)
    local p
    p=$(wg show "$1" listen-port 2>/dev/null | tr -d ' \r' || true)
    # «0» = порт не назначен — для форварда бесполезен.
    [ "$p" != "0" ] && printf '%s' "$p" || true
}

# Сохранённый клиентский конфиг прошлой установки валиден против текущего
# серверного conf: ключ клиента числится пиром, серверный ключ совпадает.
# Иначе conf протух (WG переставили, пира удалили) — переиспользовать нельзя.
_wg_client_conf_valid() {  # SERVER_CONF
    local conf="$1" cli_priv cli_pub srv_priv srv_pub conf_srv_pub
    cli_priv=$(sed -n 's/^[[:space:]]*PrivateKey[[:space:]]*=[[:space:]]*//p' "$WG_CLIENT_CONF" | head -n1 | tr -d ' \r')
    [ -n "$cli_priv" ] || return 1
    cli_pub=$(printf '%s' "$cli_priv" | wg pubkey 2>/dev/null) || return 1
    sed -n 's/^[[:space:]]*PublicKey[[:space:]]*=[[:space:]]*//p' "$conf" | tr -d ' \r' \
        | grep -qxF "$cli_pub" || return 1
    srv_priv=$(sed -n 's/^[[:space:]]*PrivateKey[[:space:]]*=[[:space:]]*//p' "$conf" | head -n1 | tr -d ' \r')
    [ -n "$srv_priv" ] || return 1
    srv_pub=$(printf '%s' "$srv_priv" | wg pubkey 2>/dev/null) || return 1
    conf_srv_pub=$(sed -n 's/^[[:space:]]*PublicKey[[:space:]]*=[[:space:]]*//p' "$WG_CLIENT_CONF" | head -n1 | tr -d ' \r')
    [ "$conf_srv_pub" = "$srv_pub" ]
}

# Пир есть в conf, но мог пропасть с живого интерфейса (wg set прошлой
# установки упал молча, рестарта wg-quick не было) — досинхронизируем.
_wg_sync_live_peer() {  # IFACE
    local iface="$1" cli_priv cli_pub ips
    wg show "$iface" >/dev/null 2>&1 || return 0
    cli_priv=$(sed -n 's/^[[:space:]]*PrivateKey[[:space:]]*=[[:space:]]*//p' "$WG_CLIENT_CONF" | head -n1 | tr -d ' \r')
    [ -n "$cli_priv" ] || return 0
    cli_pub=$(printf '%s' "$cli_priv" | wg pubkey 2>/dev/null) || return 0
    if wg show "$iface" peers 2>/dev/null | grep -qxF "$cli_pub"; then return 0; fi
    ips=$(sed -n 's/^[[:space:]]*Address[[:space:]]*=[[:space:]]*//p' "$WG_CLIENT_CONF" | head -n1 | tr -d ' \r')
    [ -n "$ips" ] || return 0
    log "re-adding client peer to live $iface"
    wg set "$iface" peer "$cli_pub" allowed-ips "$ips" 2>/dev/null \
        || log "warning: wg set $iface failed - restart wg-quick@$iface manually"
}

# Добавляет отдельного пира в СУЩЕСТВУЮЩИЙ conf и пишет клиентский конфиг.
# _wg_add_peer CONF_PATH IFACE; код 1 — нестандартный conf (нет PrivateKey/Address),
# вызывающий продолжает без клиентского конфига.
_wg_add_peer() {
    local conf="$1" iface="$2"
    local srv_priv srv_pub addr base used i candidate cli_priv cli_pub
    srv_priv=$(sed -n 's/^[[:space:]]*PrivateKey[[:space:]]*=[[:space:]]*//p' "$conf" | head -n1 | tr -d ' \r')
    [ -n "$srv_priv" ] || return 1
    srv_pub=$(printf '%s' "$srv_priv" | wg pubkey) || return 1

    # Подсеть из Interface Address (10.13.13.1/24 → база 10.13.13).
    addr=$(sed -n 's/^[[:space:]]*Address[[:space:]]*=[[:space:]]*//p' "$conf" | head -n1 | cut -d, -f1 | cut -d/ -f1 | tr -d ' \r')
    base=${addr%.*}
    [ -n "$base" ] && [ "$base" != "$addr" ] || return 1

    # Свободный хост: заняты адрес сервера и AllowedIPs существующих пиров.
    used=$(printf '%s\n' "$addr"; sed -n 's/^[[:space:]]*AllowedIPs[[:space:]]*=[[:space:]]*//p' "$conf" | tr ',' '\n' | cut -d/ -f1 | tr -d ' ')
    candidate=""
    for i in $(seq 2 254); do
        if ! printf '%s\n' "$used" | grep -qx "$base.$i"; then candidate="$base.$i"; break; fi
    done
    [ -n "$candidate" ] || return 1

    cli_priv=$(wg genkey) || return 1
    cli_pub=$(printf '%s' "$cli_priv" | wg pubkey) || return 1

    cat >> "$conf" <<EOF

[Peer]
# free-turn-proxy client (конфиг: $WG_CLIENT_CONF)
PublicKey = $cli_pub
AllowedIPs = $candidate/32
EOF

    # Применяем на живом интерфейсе без рестарта — рестарт рвал бы чужие сессии.
    if wg show "$iface" >/dev/null 2>&1; then
        wg set "$iface" peer "$cli_pub" allowed-ips "$candidate/32" 2>/dev/null || true
    fi

    ( umask 077
      cat > "$WG_CLIENT_CONF" <<EOF
[Interface]
PrivateKey = $cli_priv
Address = $candidate/32
DNS = 1.1.1.1

[Peer]
PublicKey = $srv_pub
AllowedIPs = 0.0.0.0/0
Endpoint = $ARG_WG_ENDPOINT
PersistentKeepalive = 25
EOF
    )
    return 0
}

_emit_wg_client_conf() {
    [ -f "$WG_CLIENT_CONF" ] || return 0
    if ! command -v base64 >/dev/null 2>&1; then
        log "(base64 unavailable - import client conf manually: $WG_CLIENT_CONF)"
        return 0
    fi
    emit WG_CLIENT_CONF_B64 "$(base64 < "$WG_CLIENT_CONF" | tr -d '\n')"
}

cmd_wg_setup() {
    parse_args "$@"
    [ -n "$ARG_WG_PORT" ] || die "--port required"
    [ -n "$ARG_WG_ENDPOINT" ] || die "--endpoint required"

    # Существующий WireGuard: берём первый conf в /etc/wireguard (не обязательно wg0),
    # не перетираем — добавляем своего пира и отдаём фактический порт.
    local conf iface exist_port
    conf=$(ls "$WG_DIR"/*.conf 2>/dev/null | head -n1 || true)
    if [ -n "$conf" ]; then
        iface=$(basename "$conf" .conf)
        systemctl enable --now "wg-quick@$iface" >/dev/null 2>&1 \
            || wg-quick up "$iface" >/dev/null 2>&1 \
            || log "warning: cannot bring up $iface - check WireGuard on server"
        # Порт: у живого интерфейса надёжнее, чем ListenPort из conf — туда
        # и форвардит free-turn-proxy.
        exist_port=$(_wg_runtime_port "$iface")
        [ -n "$exist_port" ] || exist_port=$(_wg_existing_port "$conf")
        case "$exist_port" in ''|*[!0-9]*) exist_port="" ;; esac
        # Готовый клиентский конфиг переиспользуем только пока он валиден
        # против текущего conf — протухший даёт мёртвый туннель у клиента.
        if [ -f "$WG_CLIENT_CONF" ] && ! _wg_client_conf_valid "$conf"; then
            log "stored client conf is stale - regenerating peer"
            rm -f "$WG_CLIENT_CONF"
        fi
        if [ ! -f "$WG_CLIENT_CONF" ]; then
            _wg_add_peer "$conf" "$iface" \
                || log "cannot add peer to $conf - import client conf manually"
        fi
        if [ -f "$WG_CLIENT_CONF" ]; then _wg_sync_live_peer "$iface"; fi
        emit WG_EXISTS yes
        emit WG_PORT "${exist_port:-$ARG_WG_PORT}"
        _emit_wg_client_conf
        echo "RESULT=ok"
        trap - EXIT
        return
    fi

    # probe видел WG, но conf вне /etc/wireguard (docker/wg-easy и т.п.) — пира
    # добавить некуда. Используем как бэкенд как есть, conf импортируется вручную.
    if [ "$ARG_WG_ADOPT" = "1" ]; then
        log "WireGuard managed externally - import client conf manually"
        emit WG_EXISTS yes
        emit WG_PORT "$ARG_WG_PORT"
        echo "RESULT=ok"
        trap - EXIT
        return
    fi

    if ! command -v wg >/dev/null 2>&1 || ! command -v wg-quick >/dev/null 2>&1; then
        log "installing wireguard-tools"
        _wg_pkg_install
        command -v wg >/dev/null 2>&1 && command -v wg-quick >/dev/null 2>&1 \
            || die "wireguard-tools install failed"
    fi

    # Порт свободен? best-effort через ss (если есть).
    if command -v ss >/dev/null 2>&1 && ss -uln 2>/dev/null | grep -q ":$ARG_WG_PORT "; then
        die "udp port $ARG_WG_PORT busy"
    fi

    mkdir -p "$WG_DIR"
    echo 'net.ipv4.ip_forward = 1' > /etc/sysctl.d/99-free-turn-proxy-wg.conf 2>/dev/null || true
    sysctl -q -w net.ipv4.ip_forward=1 >/dev/null 2>&1 || true

    local wan srv_priv srv_pub cli_priv cli_pub
    wan=$(ip route show default 2>/dev/null | awk '/default/ {print $5; exit}' || true)
    [ -z "$wan" ] && log "WAN interface not detected; NAT rules skipped"
    # Без iptables PostUp валит wg-quick up целиком — лучше туннель без NAT, чем фейл.
    if [ -n "$wan" ] && ! command -v iptables >/dev/null 2>&1; then
        wan=""
        log "iptables not found; NAT rules skipped"
    fi

    ( umask 077; wg genkey > "$WG_DIR/server.key"; wg genkey > "$WG_DIR/client.key" )
    srv_priv=$(cat "$WG_DIR/server.key"); srv_pub=$(wg pubkey < "$WG_DIR/server.key")
    cli_priv=$(cat "$WG_DIR/client.key"); cli_pub=$(wg pubkey < "$WG_DIR/client.key")

    local postup="" postdown=""
    if [ -n "$wan" ]; then
        postup="PostUp = iptables -A FORWARD -i %i -j ACCEPT; iptables -A FORWARD -o %i -j ACCEPT; iptables -t nat -A POSTROUTING -o $wan -j MASQUERADE"
        postdown="PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -D FORWARD -o %i -j ACCEPT; iptables -t nat -D POSTROUTING -o $wan -j MASQUERADE"
    fi

    ( umask 077
      cat > "$WG_CONF" <<EOF
[Interface]
Address = $WG_NET.1/24
ListenPort = $ARG_WG_PORT
PrivateKey = $srv_priv
$postup
$postdown

[Peer]
# первый клиент (его конфиг — $WG_CLIENT_CONF)
PublicKey = $cli_pub
AllowedIPs = $WG_NET.2/32
EOF
    )

    # Полуподнятый интерфейс прошлой неудачной попытки даёт «wg0 already exists»
    # на wg-quick up — сносим перед стартом. modprobe — свежепоставленный пакет
    # мог ещё не загрузить модуль ядра.
    if ip link show "$WG_IFACE" >/dev/null 2>&1; then
        wg-quick down "$WG_IFACE" >/dev/null 2>&1 \
            || ip link delete "$WG_IFACE" >/dev/null 2>&1 || true
    fi
    modprobe wireguard >/dev/null 2>&1 || true

    if ! systemctl enable --now "wg-quick@$WG_IFACE" >/dev/null 2>&1; then
        local up_out=""
        if ! up_out=$(wg-quick up "$WG_IFACE" 2>&1); then
            # Хвост реальной ошибки — в лог, иначе диагностировать нечего.
            log "wg-quick: $(printf '%s' "$up_out" | tail -n 3 | tr '\n' ';')"
            die "wg-quick up $WG_IFACE failed"
        fi
        # Поднялись вручную — автозапуск после ребута всё равно включаем.
        systemctl enable "wg-quick@$WG_IFACE" >/dev/null 2>&1 || true
    fi

    # Endpoint = локальный free-turn-proxy клиент на устройстве; рантайм приложения
    # подменяет его при поднятии туннеля, тут — рабочий дефолт.
    ( umask 077
      cat > "$WG_CLIENT_CONF" <<EOF
[Interface]
PrivateKey = $cli_priv
Address = $WG_NET.2/32
DNS = 1.1.1.1

[Peer]
PublicKey = $srv_pub
AllowedIPs = 0.0.0.0/0
Endpoint = $ARG_WG_ENDPOINT
PersistentKeepalive = 25
EOF
    )

    emit WG_EXISTS no
    emit WG_PORT "$ARG_WG_PORT"
    _emit_wg_client_conf
    echo "RESULT=ok"
    trap - EXIT
}

_kill_old_services() {
    log "stopping old services (vk-turn-proxy) if any"
    if has_systemd; then
        systemctl stop vk-turn-proxy.service 2>/dev/null || true
    fi
    pkill -9 -f "vk-turn-proxy" 2>/dev/null || true

    local port="${ARG_LISTEN##*:}"
    if [ -n "$port" ] && [[ "$port" =~ ^[0-9]+$ ]]; then
        fuser -k -9 -n tcp "$port" 2>/dev/null || true
        fuser -k -9 -n udp "$port" 2>/dev/null || true
    fi
}

# Best-effort: открыть внешний udp-порт в UFW, если он активен. Прочие firewall
# не трогаем (cloud-провайдеры обычно рулят портами своей панелью).
_open_firewall() {
    local port="${ARG_LISTEN##*:}"
    [[ "$port" =~ ^[0-9]+$ ]] || return 0
    if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
        ufw allow "${port}/udp" >/dev/null 2>&1 || true
    fi
}

cmd_start() {
    parse_args "$@"
    [ -n "$ARG_LISTEN" ]  || die "--listen required"
    [ -n "$ARG_CONNECT" ] || die "--connect required"

    _kill_old_services
    _open_firewall

    case "$(current_runtime)" in
        systemd) cmd_start_systemd ;;
        nohup)   cmd_start_nohup ;;
        *)       die "unknown runtime" ;;
    esac
}

_stop_nohup() {
    if [ -f "$PIDFILE" ]; then
        local pid
        pid=$(cat "$PIDFILE" 2>/dev/null || echo "")
        if [ -n "$pid" ]; then
            kill "$pid" 2>/dev/null || true
            sleep 1
            kill -9 "$pid" 2>/dev/null || true
        fi
        rm -f "$PIDFILE"
    fi
    pkill -9 -f "^$PREFIX/server-linux-" 2>/dev/null || true
    rm -f "$OBFFILE"
    rm -f "$ENVFILE"
}

_stop_systemd() {
    # Не disable — autostart должен сохраняться между ручными stop/start.
    systemctl stop "$UNIT_NAME" 2>/dev/null || true
    rm -f "$ENVFILE"
}

cmd_stop() {
    case "$(current_runtime)" in
        systemd) _stop_systemd ;;
        nohup)   _stop_nohup ;;
    esac
    emit STOPPED yes
    echo "RESULT=ok"
    trap - EXIT
}

cmd_logs() {
    parse_args "$@"
    case "$(current_runtime)" in
        systemd)
            if command -v journalctl >/dev/null 2>&1; then
                journalctl -u "$UNIT_NAME" -n "$ARG_TAIL" --no-pager --output=cat 2>/dev/null \
                    | sed 's/^/LOG: /' || log "(journalctl read failed)"
            else
                log "(journalctl unavailable)"
            fi
            ;;
        nohup)
            if [ -f "$LOGFILE" ]; then
                tail -n "$ARG_TAIL" "$LOGFILE" | sed 's/^/LOG: /'
            else
                log "(log empty)"
            fi
            ;;
    esac
    echo "RESULT=ok"
    trap - EXIT
}

main() {
    [ $# -ge 1 ] || die "no subcommand"
    local sub=$1
    shift
    case "$sub" in
        probe)        cmd_probe ;;
        install)      cmd_install "$@" ;;
        wg-setup)     cmd_wg_setup "$@" ;;
        start)        cmd_start "$@" ;;
        stop)         cmd_stop ;;
        logs)         cmd_logs "$@" ;;
        *) die "unknown subcommand: $sub" ;;
    esac
}

main "$@"
