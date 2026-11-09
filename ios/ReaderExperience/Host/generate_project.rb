require 'xcodeproj'

root = File.expand_path(__dir__)
project = Xcodeproj::Project.new(File.join(root, 'ReaderHost.xcodeproj'))
app = project.new_target(:application, 'ReaderHost', :ios, '17.0')
tests = project.new_target(:ui_test_bundle, 'ReaderHostUITests', :ios, '17.0')
tests.add_dependency(app)
app.build_configurations.each do |config|
  config.build_settings.merge!('PRODUCT_BUNDLE_IDENTIFIER' => 'local.coach.syntheticreader', 'GENERATE_INFOPLIST_FILE' => 'YES', 'INFOPLIST_KEY_UILaunchScreen_Generation' => 'YES', 'SWIFT_VERSION' => '5.0', 'TARGETED_DEVICE_FAMILY' => '1,2')
end
tests.build_configurations.each do |config|
  config.build_settings.merge!('PRODUCT_BUNDLE_IDENTIFIER' => 'local.coach.syntheticreader.uitests', 'GENERATE_INFOPLIST_FILE' => 'YES', 'TEST_TARGET_NAME' => 'ReaderHost', 'SWIFT_VERSION' => '5.0')
end
group = project.main_group.new_group('Sources')
Dir[File.join(root, '../Sources/ReaderExperience/*.swift')].sort.each do |path|
  app.source_build_phase.add_file_reference(group.new_file("../Sources/ReaderExperience/#{File.basename(path)}"))
end
app.source_build_phase.add_file_reference(group.new_file('ReaderHost.swift'))
tests.source_build_phase.add_file_reference(group.new_file('ReaderHostUITests.swift'))
app.resources_build_phase.add_file_reference(group.new_file('Resources/synthetic.epub'))
package = project.new(Xcodeproj::Project::Object::XCRemoteSwiftPackageReference)
package.repositoryURL = 'https://github.com/readium/swift-toolkit.git'
package.requirement = { 'kind' => 'exactVersion', 'version' => '3.11.1' }
project.root_object.package_references << package
%w[ReadiumShared ReadiumStreamer ReadiumNavigator].each do |name|
  product = project.new(Xcodeproj::Project::Object::XCSwiftPackageProductDependency)
  product.package = package
  product.product_name = name
  app.package_product_dependencies << product
  build = project.new(Xcodeproj::Project::Object::PBXBuildFile)
  build.product_ref = product
  app.frameworks_build_phase.files << build
end
project.save
scheme = Xcodeproj::XCScheme.new
scheme.add_build_target(app)
scheme.set_launch_target(app)
scheme.add_test_target(tests)
scheme.save_as(project.path, 'ReaderHost', true)
