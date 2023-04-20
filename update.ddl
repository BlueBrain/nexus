update scoped_states set value = value || '{"types":["https://bluebrain.github.io/nexus/vocabulary/Project"]}'
where type = 'project';

update scoped_states set value =  value || '{"types":["https://bluebrain.github.io/nexus/vocabulary/Schema"]}'
where type = 'schema';

update scoped_states set value =  value || '{"types":["https://bluebrain.github.io/nexus/vocabulary/Resolver", "https://bluebrain.github.io/nexus/vocabulary/InProject"]}'
where type = 'resolver' and id = 'https://bluebrain.github.io/nexus/vocabulary/defaultInProject';

update scoped_states set value =  value || '{"types":["https://bluebrain.github.io/nexus/vocabulary/Resolver", "https://bluebrain.github.io/nexus/vocabulary/CrossProject"]}'
where type = 'resolver' and id != 'https://bluebrain.github.io/nexus/vocabulary/defaultInProject';

update scoped_states set value =  value ||  '{"types":["https://bluebrain.github.io/nexus/vocabulary/File"]}'
where type = 'file';

update scoped_states set value =  value || '{"types":["https://bluebrain.github.io/nexus/vocabulary/Storage", "https://bluebrain.github.io/nexus/vocabulary/DiskStorage"]}'
where type = 'storage'and value->'value'->>'@type' = 'DiskStorageValue';

update scoped_states set value =  value || '{"types":["https://bluebrain.github.io/nexus/vocabulary/Storage", "https://bluebrain.github.io/nexus/vocabulary/RemoteDiskStorage"]}'
where type = 'storage'and value->'value'->>'@type' = 'RemoteDiskStorageValue';

update scoped_states set value = value || '{"types":["https://bluebrain.github.io/nexus/vocabulary/View", "https://bluebrain.github.io/nexus/vocabulary/ElasticSearchView"]}'
where type = 'elasticsearch' and value->'value'->>'@type' = 'IndexingElasticSearchViewValue';

update scoped_states set value = value || '{"types":["https://bluebrain.github.io/nexus/vocabulary/View", "https://bluebrain.github.io/nexus/vocabulary/AggregateElasticSearchView"]}'
where type = 'elasticsearch' and value->'value'->>'@type' = 'AggregateElasticSearchViewValue';

update scoped_states set value =  value || '{"types":["https://bluebrain.github.io/nexus/vocabulary/View", "https://bluebrain.github.io/nexus/vocabulary/SparqlView"]}'
where type = 'blazegraph' and value->'value'->>'@type' = 'IndexingBlazegraphViewValue';

update scoped_states set value = value || '{"types":["https://bluebrain.github.io/nexus/vocabulary/View", "https://bluebrain.github.io/nexus/vocabulary/AggregateSparqlView"]}'
where type = 'blazegraph' and value->'value'->>'@type' = 'AggregateBlazegraphViewValue';

update scoped_states set value = value || '{"types":["https://bluebrain.github.io/nexus/vocabulary/View", "https://bluebrain.github.io/nexus/vocabulary/CompositeView"]}'
where type = 'compositeviews';