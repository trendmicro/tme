## Exchanges Portal README


### Prerequisites

+ Ruby 1.9.3 
+ Rubygems 1.8
+ Bundler 1.0.10 and gems listed in Gemfile

### Running
Portal is well configured as a [Rack](http://rack.rubyforge.org/) app with [Bundler](http://gembundler.com). 
After installing all prerequisites, just running ` $ bundle install ` and then ` $ rackup `. You may need to
specify customized parameters to [Rake](http://rack.rubyforge.org/).

This project uses thin as default server, `thin start -p [port] -d -s thread_number` is recommended. For using other
rack-compatible server, please edit Gemfile by yourself.

### Configuration

##### rrddir 
The default value is ./rrd.
you can customize it when racking it up `$ rrddir=/tmp rackup` 

##### config/canvas.rb
Rules for plotting RRD graph are declared in canvas.rb. Data sources are declared first then Pictures.
The syntax are shown here.

data source

    ds :source_label do
        source      :source_name_in_rrd_file
        consolid    :type                       # :average, :last, etc
        consolid    "#......"
    end

picture

    picture :picture_label do
        line :source_label_declared_previously
        line :yet_another_source_label
    end



### Copyright
MIT(X11) Lincese (C) 2011 shelling (Jia-Wei Hsu)
