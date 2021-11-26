find -type f \( -name "*.xml" -o -name "*.properties" -o -name "*.java" \) -exec sed -i "s/999-SNAPSHOT/$1/g" {} +
