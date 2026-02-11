FROM python:3.12-slim
WORKDIR /app

COPY requirements.txt .
RUN apt-get update && apt-get install -y --no-install-recommends gcc python3-dev \
    && pip install --no-cache-dir -r requirements.txt \
    && apt-get purge -y gcc python3-dev && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

COPY src/ src/
COPY config.docker.yml config.yml

ENTRYPOINT ["python", "-m", "src.main", "--config", "config.yml"]
