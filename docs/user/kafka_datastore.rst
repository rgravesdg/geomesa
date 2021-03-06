Kafka Data Store
================

The GeoMesa Kafka Data Store is found in ``geomesa-kafka/geomesa-kafka-datastore/geomesa-kafka-$KAFKAVERSION-datastore/`` in the source
distribution.

The ``KafkaDataStore`` is an implementation of the GeoTools
``DataStore`` interface that is backed by Apache Kafka. The
implementation supports the ability for feature producers to instantiate
a ``KafkaDataStore`` in *producer* mode to persist data into the data
store and for consumers to instantiate a ``KafkaDataStore`` in
*consumer* mode to read data from the data store. The producer and
consumer data stores can be run on separate servers. The only
requirement is that they can connect to the same instance of Apache
Kafka.

A ``KafkaDataStore`` in consumer mode supports two types of
``SimpleFeatureSource``'s: *live* and *replay*. A Kafka Consumer Feature
Source operating in *live* mode continually pulls data from the end of
the message queue (e.g. latest time) and always represents the latest
state of the simple features. A Kafka Consumer Feature Source operating
in *replay* mode will pull data from a specified time interval in the
past and can provide features as they existed at any point in time
within that interval.

Installation
------------

Use of the Kafka data store does not require server-side configuration,
however, a ``geomesa-site.xml`` configuration file is available for
setting system properties.

This file can be found at ``conf/geomesa-site.xml`` of the Kafka
distribution. The default settings for GeoMesa are stored in
``conf/geomesa-site.xml.template``. Do not modify this file directly as it
is never read, instead copy the desired configurations into
geomesa-site.xml.

By default command line parameters will take precedence over this
configuration file. If you wish a configuration item to always take
precedence, even over command line parameters change the ``<final>``
tag to true.

By default configuration properties with empty values will not be
applied, you can change this by marking a property as final.

Usage/Configuration
-------------------

To create a ``KafkaDataStore`` there are two required properties, one
for the Apache Kafka connection, "brokers", and one for the Apache
Zookeeper connection, "zookeepers". An optional parameter, "zkPath" is
used to specify a path in Zookeeper under which schemas are stored. If
no "zkPath" is specified then a default path will be used. Another
optional parameter, "isProducer", is used to create a ``KafkaDataStore``
in *producer* or *consumer* mode. This parameter defaults to false, i.e.
by default a Kafka Consumer Data Store will be created. The same set of
configuration parameters, with the exception of "isProducer" must be
used to create both the Kafka Producer Data Store and the Kafka Consumer
Data Store.

After a ``KafkaDataStore`` has been created, additional simple feature
type specific hints must be provided. These hints are stored in the user
data of the ``SimpleFeatureType``. Use the ``KafkaDataStoreHelper`` to
create a copy of your ``SimpleFeatureType`` with the hints added. Then
call ``dataStore.createSchema(sft)`` where ``sft`` is the
``SimpleFeatureType`` returned by the ``KafkaDataStoreHelper``. This
must be done once per Simple Feature Type. The Simple Feature Type along
with hints are stored in Zookeeper so if ``createSchema(sft)`` is called
on the Kafka Data Store Producer it cannot be called on the Kafka
Consumer Data Store Consumer.

Data Producers
--------------

First, create the data store. For example:

.. code-block:: java

    String brokers = ...
    String zookeepers = ...
    String zkPath = ...

    // build parameters map
    Map<String, Serializable> params = new HashMap<>();
    params.put("brokers", brokers);
    params.put("zookeepers", zookeepers);
    params.put("isProducer", Boolean.TRUE);

    // optional
    params.put("zkPath", zkPath);

    // create the data store
    KafkaDataStoreFactory factory = new KafkaDataStoreFactory();
    DataStore producerDs = factory.createDataStore(params);

Next, create the schema. Each data store can have one or many schemas.
For example:

.. code-block:: java

    SimpleFeatureType sft = ...
    SimpleFeatureType streamingSFT = KafkaDataStoreHelper.createStreamingSFT(sft, zkPath);
    producerDs.createSchema(streamingSFT);

The call to ``KafkaDataStoreHelper.createSchema`` creates a copy of the
``sft`` with the required hint added. In this case the hint is the name
of the Kafka topic. The ``zkPath`` parameter is uses to make the Kafka
topic name unique to the ``zkPath`` used by the ``KafkaDataStore`` so
that the same ``SimpleFeatureType`` can be used by multiple
``KafkaDataStore``\ s where each data store has a different ``zkPath``.
Specifically, the resulting Kafka topic's name will be zkPath-sftName
where the forward slashes in the ``zkPath`` are replaced by ``-``.  e.g. creating a schema
with a ``zkPath`` of /geomesa/ds/kafka with an ``sft`` called example_sft will
create a Kafka topic called geomesa-ds-kafka-example_sft.

The ``createSchema`` method will throw an exception if the given
``SimpleFeatureType`` does not contain the required hint, i.e., if it
was not created by the ``KafkaDataStoreHelper``.

Now, you can create or update simple features:

.. code-block:: java

    // the name of the simple feature type -  will be the same as sft.getTypeName();
    String typeName = streamingSFT.getTypeName();

    FeatureWriter<SimpleFeatureType, SimpleFeature> fw =
            producerDs.getFeatureWriter(typeName, null, Transaction.AUTO_COMMIT);
    SimpleFeature sf = fw.next();
    // set properties on sf
    fw.write();

Delete simple features:

.. code-block:: java

    SimpleFeatureStore producerStore = (SimpleFeatureStore) producerDs.getFeatureSource(typeName);
    FilterFactory2 ff = CommonFactoryFinder.getFilterFactory2();

    String id = ...
    producerStore.removeFeatures(ff.id(ff.featureId(id)));

And, clear (delete all) features:

.. code-block:: java

    producerStore.removeFeatures(Filter.INCLUDE);

Each operation that creates, modifies, deletes, or clears simple
features results in a message being sent to the Kafka topic.

Data Consumers
--------------

First, create the data store. For example:

.. code-block:: java

    String brokers = ...
    String zookeepers = ...
    String zkPath = ...

    // build parameters map
    Map<String, Serializable> params = new HashMap<>();
    params.put("brokers", brokers);
    params.put("zookeepers", zookeepers);

    // optional - the default is false
    params.put("isProducer", Boolean.FALSE);

    // optional
    params.put("zkPath", zkPath);

    // create the data store
    KafkaDataStoreFactory factory = new KafkaDataStoreFactory();
    DataStore consumerDs = factory.createDataStore(params);

The ``brokers``, ``zookeepers``, and ``zkPath`` parameters must be
consistent with the values used to create the Kafka Data Store Producer.

Because ``createSchema`` was called on the Kafka Data Store Producer, it
does not need to be called on the Consumer. Calling ``createSchema``
with a ``SimpleFeatureType`` that has already been created will result
in an exception being thrown. Note that all ``SimpleFeature``\ s
returned by the Kafka Data Store consumer will have a
``SimpleFeatureType`` equal to the ``streamingSFT`` created when setting
up the producer, i.e. the ``SimpleFeatureType`` will include the hint
added by ``KafkaDataStoreHelper.createStreamingSFT``.

Now that the Kafka Data Store Consumer has been created it can be
queried in either *live* or *replay* mode.

Live Mode
---------

Live mode is the default and requires no extra setup. In this mode the
``SimpleFeatureSource`` contains the current state of the
``KafkaDataStore``. As ``SimpleFeatures`` are created, modified,
deleted, or cleared by the Kafka Data Store Producer, the current state
is updated. All queries to the ``SimpleFeatureSource`` are queries
against the current state. For example:

.. code-block:: java

    String typeName = ...
    SimpleFeatureSource liveFeatureSource = consumerDs.getFeatureSource(typeName);

    Filter filter = ...
    liveFeatureSource.getFeatures(filter);

It is also possible to provide a CQL filter to the getFeatureSource method call which will ensure
the resulting ``FeatureSource`` only contains certain records. Providing a filter to reduce the number of
returned records will provide a performance boost when using the featureSource.

.. code-block:: java

    String typeName = ...
    SimpleFeatureSource liveFeatureSource = consumerDs.getFeatureSource(typeName, filter);

Replay Mode
-----------

Replay mode allows the a user to query the ``KafkaDataStore`` as it
existed at any point in the past. Queries against a Kafka Replay Simple
Feature source specify a historical time to query and only the set and
version of ``SimpleFeature``\ s that existed at that point in time will
be used to answer the query.

In order to use Replay mode some additional hints are required: the
start and end times of the replay window and a read behind duration:

.. code-block:: java

    Instant replayStart = ...
    Instant replayEnd = ...
    Duration readBehind = ...
    ReplayConfig replayConfig = new ReplayConfig(replayStart, replayEnd, readBehind);

The replay window is simply an optimization that allows the Kafka Replay
Feature Source to load, at initialization time, all state changes that
occur within the window. Any query for a time outside of the window will
return no results even if features existed at that time.

The read behind is the amount of time used to rebuild state. For
example, if ``readBehind = 5s`` then for a query requesting state at
``time = t`` all state changes that occurred between ``t - 5s`` and
``t`` will be used to build the state at time ``t`` which will then be
used to answer the query. Selecting an appropriate read behind requires
an understanding of the producer. The expected uses case is a producer
that updates every simple feature, even if it hasn't changed, at a
regular interval. For example, if the producer is updating every ``x``
seconds then a read behind of ``x + 1s`` might be appropriate.

During initialization of the Kafka Replay Feature Source all state
changes from ``replayStart - readBehind`` to ``replayEnd`` will be read
and cached. As the size of the replay window and read behind increases
so does the amount of data that must be read and cashed. So, both the
size of the window and the read behind should be kept as small as
possible.

After creating the ``ReplayConfig`` pass it, along with the
``streamingSFT`` to the ``KafkaDataStoreHelper``:

.. code-block:: java

    SimpleFeatureType streamingSFT = consumerDs.getSchema(typeName);
    SimpleFeatureType replaySFT = KafkaDataStoreHelper.createReplaySFT(streamingSFT, replayConfig);

The ``streamingSFT`` passed to ``createReplaySFT`` must contain the
hints added by ``KafkaDataStoreHelper.createStreamingSFT``. The easiest
way to ensure this is to call ``consumerDs.getSchema(typeName)``. The
``SimpleFeatureType`` returned by ``createReplaySFT`` will contain the
hint added by ``createStreamingSFT`` as well as a a hint containing the
``ReplayConfig``. Additionally the ``replaySFT`` will have a different
name then then ``streamingSFT``. This is to differentiate *live* and
*replay* ``SimpleFeatureType``\ s. The ``replaySFT`` will also contain
an additional attribute, ``KafkaLogTime``, of type ``java.util.Date``
which represents the historical query time.

After creating the ``replaySFT`` the Kafka Replay Feature Source may be
created:

.. code-block:: java

    consumerDs.createSchema(replaySFT);

    String replayTimeName = replaySFT.getTypeName();
    SimpleFeatureSource replayFeatureSource = consumerDs.getFeatureSource(replayTimeName);

The call to ``createSchema`` is required because the ``replaySFT`` is a
new ``SimpleFeatureType``.

Finally the Kafka Replay Consumer Feature Source can be queried:

.. code-block:: java

    Instant historicalTime = ...
    Filter timeFilter = ff.and(filter, ReplayTimeHelper.toFilter(historicalTime));

    replayFeatureSource.getFeatures(timeFilter);

Listening for Feature Events
----------------------------

The GeoTools API includes a mechanism to fire off a `FeatureEvent`_ object each time
that there is an "event," which occurs when data are added, changed, or deleted in a
`SimpleFeatureSource`_. A client may implement a `FeatureListener`_, which has a single
method called ``changed()`` that is invoked each time that each `FeatureEvent`_ is
fired.

Three types of messages are produced by a GeoMesa Kafka producer, which can be read
by a GeoMesa Kafka consumer. For a live consumer feature source, reading each of these
messages causes a `FeatureEvent`_ to be fired, as summarized in the table below:

+----------------+------------------------------------+-----------------------+----------------------+--------------------+
| Message read   | Meaning                            | Class of event fired  | `FeatureEvent.Type`_ | Filter             |
+================+====================================+=======================+======================+====================+
| CreateOrUpdate | A single feature with a given id   | ``KafkaFeatureEvent`` | ``CHANGED``          | ``IN (<id>)``      |
|                | has been added; this               |                       |                      |                    |
|                | may be a new feature, or an update |                       |                      |                    |
|                | of an existing feature.            |                       |                      |                    |
+----------------+------------------------------------+-----------------------+----------------------+--------------------+
| Delete         | The feature with the given id      | ``FeatureEvent``      | ``REMOVED``          | ``IN (<id>)``      |
|                | has been removed.                  |                       |                      |                    |
+----------------+------------------------------------+-----------------------+----------------------+--------------------+
| Clear          | All features have been removed.    | ``FeatureEvent``      | ``REMOVED``          | ``Filter.INCLUDE`` |
+----------------+------------------------------------+-----------------------+----------------------+--------------------+

For a CreateOrUpdate message, the `FeatureEvent`_ returned is actually a ``KafkaFeatureEvent``,
which subclasses `FeatureEvent`_. ``KafkaFeatureEvent`` adds a ``feature()`` method, which returns
the ``SimpleFeature`` added or updated.

To register a `FeatureListener`_, create the `SimpleFeatureSource`_ from a live GeoMesa
Kafka consumer data store, and use the ``addFeatureListener()`` method. For example, the
following listener simply prints out the events it receives:

.. code-block:: java

    // the live consumer must be created before the producer writes features
    // in order to read streaming data.
    // i.e. the live consumer will only read data written after its instantiation
    SimpleFeatureSource consumerFS = consumerDS.getFeatureSource(sftName);
    FeatureListener listener = new FeatureListener() {
        @Override
        public void changed(FeatureEvent featureEvent) {
            System.out.println("Received FeatureEvent of Type: " + featureEvent.getType());

            if (featureEvent.getType() == FeatureEvent.Type.CHANGED &&
                    featureEvent instanceof KafkaFeatureEvent) {
                SimpleFeature feature = ((KafkaFeatureEvent) featureEvent).feature();
                System.out.println(feature);
            }

            if (featureEvent.getType() == FeatureEvent.Type.REMOVED) {
                System.out.println("Received Delete for filter: " + featureEvent.getFilter());
            }
        }
    }
    consumerFS.addFeatureListener(listener);

At cleanup time, it is important to unregister the feature listener with ``removeFeatureListener()``.
For example, for code run in a bean in GeoServer, the ``javax.annotation.PreDestroy`` annotation may
be used to mark the method that does the deregistration:

.. code-block:: java

    @PreDestroy
    public void dispose() throws Exception {
        consumerFS.removeFeatureListener(listener);
        // other cleanup
    }

.. _FeatureEvent: http://docs.geotools.org/stable/javadocs/org/geotools/data/FeatureEvent.html
.. _FeatureEvent.Type: http://docs.geotools.org/stable/javadocs/org/geotools/data/FeatureEvent.Type.html
.. _FeatureListener: http://docs.geotools.org/stable/javadocs/org/geotools/data/FeatureListener.html
.. _SimpleFeatureSource: http://docs.geotools.org/stable/javadocs/org/geotools/data/simple/SimpleFeatureSource.html


Using the Kafka Data Store in GeoServer
---------------------------------------

See :doc:`./geoserver`.

Command Line Tools
------------------

The KafkaGeoMessageFormatter, part of geomesa-kafka-datastore, may be
used with the ``kafka-console-consumer``, part of Apache Kafka. In order
to use this formatter call the kafka-console consumer with these
additional arguments:

.. note::

    Replace ``$KAFKAVERSION`` below with the appropriate version number for your environment: 08, 09, or 10.
    e.g. ``org.locationtech.geomesa.kafka08.KafkaGeoMessageFormatter``

.. code-block:: bash

    --formatter org.locationtech.geomesa.kafka$KAFKAVERSION.KafkaGeoMessageFormatter
    --property sft.name={sftName}
    --property sft.spec={sftSpec}

In order to pass the spec via a command argument all ``%`` characters
must be replaced by ``%37`` and all ``=`` characters must be replaced by
``%61``.

A slightly easier to use but slightly less flexible alternative is to
use the ``KafkaDataStoreLogViewer`` instead of the
``kafka-console-consumer``. To use the ``KafkaDataStoreLogViewer`` first
copy the geomesa-kafka-geoserver-plugin.jar to $KAFKA\_HOME/libs. Then
create a copy of $KAFKA\_HOME/bin/kafka-console-consumer.sh called
"kafka-ds-log-viewer" and in the copy replace the classname in the exec
command at the end of the script with
``org.locationtech.geomesa.kafka$KAFKAVERSION.KafkaDataStoreLogViewer``.

The ``KafkaDataStoreLogViewer`` requires three arguments:
``--zookeeper``, ``--zkPath``, and ``--sftName``. It also supports an
optional argument ``--from`` which accepts values ``oldest`` and
``newest``. ``oldest`` is equivalent to specifying ``--from-beginning``
when using the ``kafka-console-consumer`` and ``newest`` is equivalent
to not specifying ``--from-beginning``.

For example:

.. code-block:: bash

    $ kafka-ds-log-viewer --zookeeper {zookeeper} --zkPath {zkPath} --sftName {sftName}

The ``KafkaDataStoreLogViewer`` loads the ``SimpleFeatureType`` from
Zookeeper so it does not need to be passed via the command line.