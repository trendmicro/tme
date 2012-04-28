
%ifnos darwin
%define __os_install_post \
    /usr/lib/rpm/brp-compress \
    %{!?__debug_package:/usr/lib/rpm/brp-strip %{__strip}} \
    /usr/lib/rpm/brp-strip-static-archive %{__strip} \
    /usr/lib/rpm/brp-strip-comment-note %{__strip} %{__objdump} \
    /usr/lib/rpm/brp-python-bytecompile \
%{nil}
%endif

%define name tme-common

Summary: TME Common
Name: %{name}
Version: %{version}
Release: %{release}
License: Trend Micro Inc.
Group: System Environment/Daemons
Source: %{name}-%{version}.tar.gz
BuildRoot: %{_tmppath}/%{name}-%{version}-root

%description

TME Common

%prep

rm -rf $RPM_BUILD_ROOT
mkdir -p $RPM_BUILD_ROOT

%setup -q

%build

%install

cp -rf * $RPM_BUILD_ROOT/

%clean

rm -rf $RPM_BUILD_ROOT

%pre

if [ "`getent passwd TME`" == "" ]; then
    echo "Error: must create user TME first!"
    exit 1
fi

%post
if [ "$1" = "1" ]; then
    # install
    mkdir -p /var/lib/tme
    chown TME:TME /var/lib/tme
    mkdir -p /var/log/tme
    chown TME:TME /var/log/tme
    mkdir -p /var/run/tme
    chown TME:TME /var/run/tme
fi


%files

%config /opt/trend/tme/conf/common/common-env.sh

%dir 
/opt/trend/tme

%changelog
* Tue Nov 29 2011 Scott Wang
- Initial
