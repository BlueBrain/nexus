FROM python:3.8

WORKDIR /opt/prometheus-exporter

ADD ./prometheus-blazegraph-exporter.py /opt/prometheus-exporter/prometheus-blazegraph-exporter.py

RUN chmod ugo+rx /opt/prometheus-exporter/prometheus-blazegraph-exporter.py

RUN pip install python-dateutil prometheus_client requests argparse

EXPOSE 9193

ENTRYPOINT ["python", "/opt/prometheus-exporter/prometheus-blazegraph-exporter.py"]
