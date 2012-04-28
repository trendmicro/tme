
%ifnos darwin
%define __os_install_post \
    /usr/lib/rpm/brp-compress \
    %{!?__debug_package:/usr/lib/rpm/brp-strip %{__strip}} \
    /usr/lib/rpm/brp-strip-static-archive %{__strip} \
    /usr/lib/rpm/brp-strip-comment-note %{__strip} %{__objdump} \
    /usr/lib/rpm/brp-python-bytecompile \
%{nil}
%endif

%define name tme-graph-editor

Summary: TME Graph Editor
Name: %{name}
Version: %{version}
Release: %{release}
License: Trend Micro Inc.
Group: System Environment/Daemons
Source: %{name}-%{version}.tar.gz
BuildRoot: %{_tmppath}/%{name}-%{version}-root
Requires: jdk, graphviz, tme-common >= 2.5-20120420Z, monit
Requires(post): /sbin/chkconfig, /sbin/service
Requires(preun): /sbin/chkconfig, /sbin/service

%description

TME Graph Editor

%prep

rm -rf $RPM_BUILD_ROOT
mkdir -p $RPM_BUILD_ROOT

%setup -q

%build

%install

cp -rf * $RPM_BUILD_ROOT/

%clean

rm -rf $RPM_BUILD_ROOT

%files
/etc/init.d/tme-graph-editor

%dir 
/opt/trend/tme/bin
/opt/trend/tme/lib
/opt/trend/tme/web

%config /opt/trend/tme/conf/graph-editor/tme-graph-editor.monit
%config /opt/trend/tme/conf/graph-editor/graph-editor.properties
%config /opt/trend/tme/conf/graph-editor/logback.xml
%config /opt/trend/tme/conf/graph-editor/ldaploginmodule.conf

%pre

if [ "`getent passwd TME`" == "" ]; then
	echo "Error: must create user TME first!"
	exit 1
fi

if [ "$1" = "1" ]; then
    # install
	usleep 1
elif [ "$1" = "2" ]; then
    # upgrade
	usleep 1
fi

%post

if [ "$1" = "1" ]; then
    # install
    usleep 1
elif [ "$1" = "2" ]; then
    # upgrade
    usleep 1
fi

%preun

/opt/trend/tme/bin/remove_tme-graph-editor.sh

if [ "$1" = "1" ]; then
    # upgrade
    usleep 1
elif [ "$1" = "0" ]; then
    # uninstall
    usleep 1
fi

%postun

if [ "$1" = "1" ]; then
    # upgrade
    usleep 1
elif [ "$1" = "0" ]; then
    # uninstall
    usleep 1
fi

%changelog
* Tue Nov 29 2011 Scott Wang
- Initial
