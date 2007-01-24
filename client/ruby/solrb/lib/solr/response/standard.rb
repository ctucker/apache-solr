# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

module Solr
  module Response
    class Standard < Solr::Response::Ruby
      include Enumerable

      def initialize(ruby_code)
        super(ruby_code)
        @response = @data['response']
        raise "response section missing" unless @response.kind_of? Hash
      end

      def total_hits
        return @response['numFound']
      end

      def start
        return @response['start']
      end

      def hits
        return @response['docs']
      end

      def max_score
        return @response['maxScore']
      end
      
      def field_facets(field)
        @data['facet_counts']['facet_fields'][field].sort {|a,b| b[1] <=> a[1]}
      end
      

      # supports enumeration of hits
      def each
        @response['docs'].each {|hit| yield hit}
      end

    end
  end
end
