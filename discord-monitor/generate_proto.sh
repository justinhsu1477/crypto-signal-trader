#!/bin/bash
# 從 proto/ 目錄產生 Python gRPC stubs
# 使用方式: cd discord-monitor && bash generate_proto.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROTO_DIR="${SCRIPT_DIR}/../proto"
OUT_DIR="${SCRIPT_DIR}/src/generated"

echo "📦 生成 Python gRPC stubs..."
echo "   Proto: ${PROTO_DIR}/monitor_config.proto"
echo "   Output: ${OUT_DIR}/"

python3 -m grpc_tools.protoc \
  -I"${PROTO_DIR}" \
  --python_out="${OUT_DIR}" \
  --grpc_python_out="${OUT_DIR}" \
  "${PROTO_DIR}/monitor_config.proto"

# 修正 import 路徑（grpcio-tools 產生的 import 不帶 package prefix）
sed -i '' 's/^import monitor_config_pb2/from . import monitor_config_pb2/' \
  "${OUT_DIR}/monitor_config_pb2_grpc.py" 2>/dev/null || true

echo "✅ 完成！生成檔案："
ls -la "${OUT_DIR}"/monitor_config_pb2*.py
