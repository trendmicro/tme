
%define __os_install_post \
    /usr/lib/rpm/brp-compress \
    %{!?__debug_package:/usr/lib/rpm/brp-strip %{__strip}} \
    /usr/lib/rpm/brp-strip-static-archive %{__strip} \
    /usr/lib/rpm/brp-strip-comment-note %{__strip} %{__objdump} \
    /usr/lib/rpm/brp-python-bytecompile \
%{nil}

%define name tme-console

Summary: TME Console
Name: %{name}
Version: %{version}
Release: %{release}
License: Trend Micro Inc.
Group: System Environment/Tools
Source: %{name}-%{version}.tar.gz
BuildRoot: %{_tmppath}/%{name}-%{version}-root
Requires: jdk, tme-mist >= 2.5-20120828Z

%description

TME Console

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
/usr/bin/tme-console
%dir 
/opt/trend/tme/lib

%config /opt/trend/tme/conf/console/ldaploginmodule.conf 

%pre

if [ "$1" = "1" ]; then
    # install
    true
elif [ "$1" = "2" ]; then
    # upgrade
    true
fi

%post


if [ "$1" = "1" ]; then
    # install
    true
elif [ "$1" = "2" ]; then
    # upgrade
    true
fi

%preun

if [ "$1" = "1" ]; then
    # upgrade
    true
elif [ "$1" = "0" ]; then
    # uninstall
    true
fi

%postun

if [ "$1" = "1" ]; then
    # upgrade
    true
elif [ "$1" = "0" ]; then
    # uninstall
    true
fi

%changelog
* Tue Nov 29 2011 Scott Wang
- Initial
