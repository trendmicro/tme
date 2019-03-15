
%define __os_install_post \
    /usr/lib/rpm/brp-compress \
    %{!?__debug_package:/usr/lib/rpm/brp-strip %{__strip}} \
    /usr/lib/rpm/brp-strip-static-archive %{__strip} \
    /usr/lib/rpm/brp-strip-comment-note %{__strip} %{__objdump} \
    /usr/lib/rpm/brp-python-bytecompile \
%{nil}
%define debug_package %{nil}

%define name tme-portal-web

Summary: TME Portal Web
Name: %{name}
Version: %{version}
Release: %{release}
License: Trend Micro Inc.
Group: System Environment/Daemons
Source: tme-portal-web-%{version}.tar.gz
BuildRoot: %{_tmppath}/%{name}-%{version}-root
Requires: ruby >= 1.9.3, ruby-bundler, rrdtool, nodejs, tme-common >= 2.5-20120802Z, monit
Requires(post): /sbin/chkconfig, /sbin/service
Requires(preun): /sbin/chkconfig, /sbin/service

%description

TME Portal Web

%prep

rm -rf $RPM_BUILD_ROOT
mkdir -p $RPM_BUILD_ROOT

%setup -q

%build

%install

cp -rf * $RPM_BUILD_ROOT

%post
# this adds a symlink of librrd.so which the rrdtool package does not install
ln -fs /usr/lib64/librrd.so.4 /usr/lib64/librrd.so

%preun
/opt/trend/tme/bin/remove_tme-portal-web.sh

if [ "$1" = "0" ]; then
# only remove the link during uninstall
    rm -f /usr/lib64/librrd.so
fi

%clean

rm -rf $RPM_BUILD_ROOT

%files
/etc/init.d/tme-portal-web

%config /opt/trend/tme/conf/portal-web/portal-web-conf.sh
%config /opt/trend/tme/conf/portal-web/tme-portal-web.monit
/opt/trend/tme/lib/portal-web
%attr(755, TME, TME) /opt/trend/tme/lib/portal-web/public/images
/opt/trend/tme/conf/portal-web
/opt/trend/tme/bin
