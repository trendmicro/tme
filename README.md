How to Build TME
================

CentOS
------

Tested on CentOS 5 and 6

Prerequisites packages:

* Sun JDK (jdk-1.6.0_21+)
* Apache Ant (1.8.2+)
* GNU g++ (gcc-c++)
* CMake (cmake)
* rpmbuild (rpm-build)
* xml-commons-apis (xml-commons-apis)

Make sure that the binary executable of ant is included in the search path, and it uses the Sun JDK to build.

Simply run `make rpm` in the top directory, and all the RPM packages of TME will be generated to `output/artifacts`

Ubuntu
------

Tested on Ubuntu 16.04 LTS (Xenial), using OpenJDK should work.

To prepare the build environment, please install the following prerequisites system-wide:

```console
$ sudo apt-get install git-core default-jdk ant g++ cmake rpm
```

TME web portal has only been verified with Ruby 1.9.3+. You have to follow this
step to use RVM to install Ruby 1.9.3:

Step 1: install related prerequisites in system:

```console
$ sudo apt-get install build-essential libssl-dev libreadline5 libreadline5-dev zlib1g zlib1g-dev curl
```

Step 2: Install RVM, which needs to install GPG keys first
( see: https://rvm.io/rvm/security ):

```console
$ curl -sSL https://rvm.io/mpapis.asc | gpg --import -
$ curl -sSL https://rvm.io/pkuczynski.asc | gpg --import -
$ curl -L get.rvm.io | bash -s stable
```

Step 3: Provision RVM with Ruby 1.9.3:

```console
$ source ~/.rvm/scripts/rvm
$ rvm install 1.9.3 ; rvm default 1.9.3
```

Step 4: Install ruby-bundler in RVM:

```console
$ gem install bundler -v 1.17.3
```

