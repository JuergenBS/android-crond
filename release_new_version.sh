#!/bin/bash

git diff-index --quiet HEAD
if [ $? != 0 ]
then
    echo Dirty work tree. Aborting.
    exit 1
fi

echo "Release new version current version is $(grep "versionCode \"" app/build.gradle | awk '{print $2}')"
read -p "Input new version:" new_version_name
new_version_code=$(($(git tag --merged | wc -l)+1))
echo New version name: $new_version_name
echo New version code: $new_version_code

sed -i "s/versionName \".*\"$/versionName \"$new_version_name\"/" app/build.gradle
sed -i "s/versionCode [0-9]*$/versionCode \"$new_version_code\"/" app/build.gradle

git add app/build.gradle
git commit -m "Bumping version to $new_version_name."
git tag $new_version_name
