info = {}
taxonomies = 'programming_language= java, neo4j_version =3-5; 3-6;'
  .split(',')
  .map { |taxonomy| taxonomy.strip }
  .reject { |t| t.empty? }
  .map { |taxonomy|
    key, value = taxonomy.split('=')
    { 'key' => key.strip, 'values' => value.strip.split(';').map(&:strip).reject(&:empty?) }
  }

require 'yaml'
info['taxonomies'] = taxonomies
p info.to_yaml
