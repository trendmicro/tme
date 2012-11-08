
%define __os_install_post \
    /usr/lib/rpm/brp-compress \
    %{!?__debug_package:/usr/lib/rpm/brp-strip %{__strip}} \
    /usr/lib/rpm/brp-strip-static-archive %{__strip} \
    /usr/lib/rpm/brp-strip-comment-note %{__strip} %{__objdump} \
    /usr/lib/rpm/brp-python-bytecompile \
%{nil}

%define name tme-mist-tools

Summary: TME MIST Toolkits
Name: %{name}
Version: %{version}
Release: %{release}
License: Trend Micro Inc.
Group: System Environment/Daemons
Source: %{name}-%{version}.tar.gz
BuildRoot: %{_tmppath}/%{name}-%{verion}-root

%description

TME MIST Toolkits

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
/usr/bin/mist-session
/usr/bin/mist-sink
/usr/bin/mist-source
/usr/bin/mist-decode
/usr/bin/mist-encode
/usr/bin/mist-count
/opt/trend/tme/lib/libprotobuf.so.4

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
