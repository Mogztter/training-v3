require 'asciidoctor/extensions' unless RUBY_ENGINE == 'opal'
require 'pathname'

include Asciidoctor

class ModuleInfoTreeProcessor < Extensions::TreeProcessor; use_dsl
  def process(document)
    if (docdir = document.attr 'docdir')
      path = File.expand_path('../build/online/asciidoctor-module-descriptor.yml', docdir)
      if File.exist?(path)
        require 'yaml'
        module_descriptor = YAML::load_file(path)
        document_slug = (document.attr 'slug')
        module_descriptor['pages'].each_with_index do |page, index|
          document.set_attribute "module-toc-link-#{index}", page['url']
          document.set_attribute "module-toc-title-#{index}", page['title']
          document.set_attribute "module-toc-slug-#{index}", page['slug']
          document.set_attribute "module-quiz-#{index}", page['quiz']
          if document_slug == page['slug']
            if page.has_key?('next')
              document.set_attr "module-next-slug", page['next']['slug'], false
              document.set_attr "module-next-title", page['next']['title'], false
            end
            document.set_attribute "module-quiz", page['quiz']
          end
        end
        document.set_attribute "module-name", module_descriptor['module_name']
      end
    end
    document
  end
end

Extensions.register do
  tree_processor ModuleInfoTreeProcessor
end
