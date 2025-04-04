#!/usr/bin/env bash

ELASTICSEARCH_INDEX_TEMPLATE_URL="http://elastic:password@elasticsearch:9200/_index_template/test_template"

# Create index template to have a quicker refresh and no replica
curl -f --silent -H "Content-Type: application/json" $ELASTICSEARCH_INDEX_TEMPLATE_URL --upload-file /config/elasticsearch/template.json &&
ln -sf /opt/docker/plugins/disabled/project-deletion.jar /opt/docker/plugins/project-deletion.jar &&
/opt/docker/bin/delta-app -Xmx4G